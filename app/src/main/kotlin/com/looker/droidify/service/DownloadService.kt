package com.looker.droidify.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import com.looker.core.common.*
import com.looker.core.common.cache.Cache
import com.looker.core.common.extension.*
import com.looker.core.common.result.Result.*
import com.looker.core.common.signature.Hash
import com.looker.core.common.signature.verifyHash
import com.looker.core.datastore.UserPreferencesRepository
import com.looker.core.datastore.model.InstallerType
import com.looker.core.model.Release
import com.looker.core.model.Repository
import com.looker.droidify.BuildConfig
import com.looker.droidify.MainActivity
import com.looker.droidify.utility.extension.android.getPackageArchiveInfoCompat
import com.looker.installer.InstallManager
import com.looker.installer.model.installFrom
import com.looker.network.Downloader
import com.looker.network.NetworkResponse
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import com.looker.core.common.R as CommonR
import com.looker.core.common.R.string as stringRes
import com.looker.core.common.R.style as styleRes

@AndroidEntryPoint
class DownloadService : ConnectionService<DownloadService.Binder>() {
	companion object {
		private const val ACTION_CANCEL = "${BuildConfig.APPLICATION_ID}.intent.action.CANCEL"
	}

	private val downloadJob = SupervisorJob()
	private val scope = CoroutineScope(Dispatchers.Main + downloadJob)

	@Inject
	lateinit var userPreferencesRepository: UserPreferencesRepository

	@Inject
	lateinit var downloader: Downloader

	private val installerType = flow {
		emit(userPreferencesRepository.fetchInitialPreferences().installerType)
	}

	@Inject
	lateinit var installer: InstallManager

	sealed class State(val packageName: String) {
		data object Idle : State("")
		data class Pending(val name: String) : State(name)
		data class Connecting(val name: String) : State(name)
		data class Downloading(val name: String, val read: Long, val total: Long?) : State(name)
		data class Error(val name: String) : State(name)
		data class Cancel(val name: String) : State(name)
		data class Success(val name: String, val release: Release) : State(name)
	}

	private val _downloadState = MutableStateFlow<State>(State.Idle)

	private class Task(
		val packageName: String, val name: String, val release: Release,
		val url: String, val authentication: String,
		val isUpdate: Boolean = false
	) {
		val notificationTag: String
			get() = "download-$packageName"
	}

	private data class CurrentTask(val task: Task, val job: Job, val lastState: State)

	private var started = false
	private val tasks = mutableListOf<Task>()
	private var currentTask: CurrentTask? = null

	private val lock = Mutex()

	inner class Binder : android.os.Binder() {
		val downloadState = _downloadState.asStateFlow()
		fun enqueue(
			packageName: String,
			name: String,
			repository: Repository,
			release: Release,
			isUpdate: Boolean = false
		) {
			val task = Task(
				packageName = packageName,
				name = name,
				release = release,
				url = release.getDownloadUrl(repository),
				authentication = repository.authentication,
				isUpdate = isUpdate
			)
			if (Cache.getReleaseFile(this@DownloadService, release.cacheFileName).exists()) {
				scope.launch { publishSuccess(task) }
			} else {
				cancelTasks(packageName)
				cancelCurrentTask(packageName)
				notificationManager.cancel(
					task.notificationTag,
					Constants.NOTIFICATION_ID_DOWNLOADING
				)
				tasks += task
				if (currentTask == null) {
					handleDownload()
				} else {
					scope.launch { _downloadState.emit(State.Pending(packageName)) }
				}
			}
		}

		fun cancel(packageName: String) {
			cancelTasks(packageName)
			cancelCurrentTask(packageName)
			handleDownload()
		}
	}

	private val binder = Binder()
	override fun onBind(intent: Intent): Binder = binder

	override fun onCreate() {
		super.onCreate()

		sdkAbove(Build.VERSION_CODES.O) {
			NotificationChannel(
				Constants.NOTIFICATION_CHANNEL_DOWNLOADING,
				getString(stringRes.downloading), NotificationManager.IMPORTANCE_LOW
			)
				.apply { setShowBadge(false) }
				.let(notificationManager::createNotificationChannel)
		}

		scope.launch {
			_downloadState
				.filter { currentTask != null }
				.collect {
					delay(400)
					publishForegroundState(false, it)
				}
		}
	}

	override fun onDestroy() {
		super.onDestroy()

		scope.cancel()
		cancelTasks(null)
		cancelCurrentTask(null)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_CANCEL) {
			currentTask?.let { binder.cancel(it.task.packageName) }
		}
		return START_NOT_STICKY
	}

	private fun cancelTasks(packageName: String?) {
		tasks.removeAll {
			(packageName == null || it.packageName == packageName) && run {
				scope.launch {
					_downloadState.emit(State.Cancel(it.packageName))
				}
				true
			}
		}
	}

	private fun cancelCurrentTask(packageName: String?) {
		currentTask?.let {
			if (packageName == null || it.task.packageName == packageName) {
				it.job.cancel()
				currentTask = null
				scope.launch {
					_downloadState.emit(State.Cancel(it.task.packageName))
				}
			}
		}
	}

	private enum class ValidationError { INTEGRITY, FORMAT, METADATA, SIGNATURE, PERMISSIONS }

	private sealed interface ErrorType {
		data object IO : ErrorType
		data object Http : ErrorType
		data object SocketTimeout : ErrorType
		data object ConnectionTimeout : ErrorType
		class Validation(val validateError: ValidationError) : ErrorType
	}

	private fun showNotificationError(task: Task, errorType: ErrorType) {
		val intent = Intent(this, MainActivity::class.java)
			.setAction(Intent.ACTION_VIEW)
			.setData(Uri.parse("package:${task.packageName}"))
			.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
			.getPendingIntent(this)
		notificationManager.notify(
			task.notificationTag,
			Constants.NOTIFICATION_ID_DOWNLOADING,
			NotificationCompat
				.Builder(this, Constants.NOTIFICATION_CHANNEL_DOWNLOADING)
				.setAutoCancel(true)
				.setSmallIcon(android.R.drawable.stat_notify_error)
				.setColor(
					ContextThemeWrapper(this, styleRes.Theme_Main_Light)
						.getColor(CommonR.color.md_theme_dark_errorContainer)
				)
				.setOnlyAlertOnce(true)
				.setContentIntent(intent)
				.apply {
					errorNotificationContent(task, errorType)
				}
				.build())
	}

	private fun NotificationCompat.Builder.errorNotificationContent(
		task: Task,
		errorType: ErrorType
	) {
		val title = if (errorType is ErrorType.Validation) stringRes.could_not_validate_FORMAT
		else stringRes.could_not_download_FORMAT
		val description = when (errorType) {
			ErrorType.ConnectionTimeout -> stringRes.connection_error_DESC
			ErrorType.Http -> stringRes.http_error_DESC
			ErrorType.IO -> stringRes.io_error_DESC
			ErrorType.SocketTimeout -> stringRes.socket_error_DESC
			is ErrorType.Validation -> when (errorType.validateError) {
				ValidationError.INTEGRITY -> stringRes.integrity_check_error_DESC
				ValidationError.FORMAT -> stringRes.file_format_error_DESC
				ValidationError.METADATA -> stringRes.invalid_metadata_error_DESC
				ValidationError.SIGNATURE -> stringRes.invalid_signature_error_DESC
				ValidationError.PERMISSIONS -> stringRes.invalid_permissions_error_DESC
			}
		}
		setContentTitle(getString(title, task.name))
		setContentText(getString(description))
	}

	private fun showNotificationInstall(task: Task) {
		val intent = Intent(this, MainActivity::class.java)
			.setAction(MainActivity.ACTION_INSTALL)
			.setData(Uri.parse("package:${task.packageName}"))
			.putExtra(MainActivity.EXTRA_CACHE_FILE_NAME, task.release.cacheFileName)
			.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
			.getPendingIntent(this)
		notificationManager.notify(
			task.notificationTag,
			Constants.NOTIFICATION_ID_DOWNLOADING,
			NotificationCompat
				.Builder(this, Constants.NOTIFICATION_CHANNEL_DOWNLOADING)
				.setAutoCancel(true)
				.setOngoing(false)
				.setSmallIcon(android.R.drawable.stat_sys_download_done)
				.setColor(
					ContextThemeWrapper(this, styleRes.Theme_Main_Light)
						.getColor(CommonR.color.md_theme_dark_primaryContainer)
				)
				.setOnlyAlertOnce(true)
				.setContentIntent(intent)
				.setContentTitle(getString(stringRes.downloaded_FORMAT, task.name))
				.setContentText(getString(stringRes.tap_to_install_DESC))
				.build()
		)
	}

	private suspend fun publishSuccess(task: Task) {
		val currentInstaller = installerType.first()
		_downloadState.emit(State.Pending(task.packageName))
		_downloadState.emit(State.Success(task.packageName, task.release))
		val autoInstallWithSessionInstaller =
			SdkCheck.canAutoInstall(task.release.targetSdkVersion)
					&& currentInstaller == InstallerType.SESSION
					&& task.isUpdate

		showNotificationInstall(task)
		if (currentInstaller == InstallerType.ROOT
			|| currentInstaller == InstallerType.SHIZUKU
			|| autoInstallWithSessionInstaller
		) {
			val installItem = task.packageName installFrom task.release.cacheFileName
			installer + installItem
		}
	}

	private suspend fun validatePackage(
		task: Task,
		file: File
	): ValidationError? = withContext(Dispatchers.IO) {
		var validationError: ValidationError? = null
		val hash = Hash(task.release.hashType, task.release.hash)
		if (!file.verifyHash(hash)) validationError = ValidationError.INTEGRITY
		yield()
		val packageInfo = packageManager.getPackageArchiveInfoCompat(file.path)
			?: return@withContext ValidationError.FORMAT
		if (packageInfo.packageName != task.packageName || packageInfo.versionCodeCompat != task.release.versionCode)
			validationError = ValidationError.METADATA
		yield()
		val signature = packageInfo.singleSignature?.calculateHash().orEmpty()
		if (signature.isEmpty() || signature != task.release.signature)
			validationError = ValidationError.SIGNATURE
		yield()
		val permissions = packageInfo.permissions?.asSequence().orEmpty().map { it.name }.toSet()
		if (!task.release.permissions.containsAll(permissions))
			validationError = ValidationError.PERMISSIONS
		yield()
		validationError
	}

	private val stateNotificationBuilder by lazy {
		NotificationCompat
			.Builder(this, Constants.NOTIFICATION_CHANNEL_DOWNLOADING)
			.setSmallIcon(android.R.drawable.stat_sys_download)
			.setColor(
				ContextThemeWrapper(this, styleRes.Theme_Main_Light)
					.getColor(CommonR.color.md_theme_dark_primaryContainer)
			)
			.addAction(
				0, getString(stringRes.cancel), PendingIntent.getService(
					this,
					0,
					Intent(this, this::class.java).setAction(ACTION_CANCEL),
					PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
				)
			)
	}

	private fun publishForegroundState(force: Boolean, state: State) {
		if (!force && currentTask == null) return
		currentTask = currentTask!!.copy(lastState = state)
		startForeground(
			Constants.NOTIFICATION_ID_DOWNLOADING,
			stateNotificationBuilder.apply {
				when (state) {
					is State.Connecting -> {
						setContentTitle(
							getString(stringRes.downloading_FORMAT, currentTask!!.task.name)
						)
						setContentText(getString(stringRes.connecting))
						setProgress(1, 0, true)
					}

					is State.Downloading -> {
						setContentTitle(
							getString(stringRes.downloading_FORMAT, currentTask!!.task.name)
						)
						if (state.total != null) {
							setContentText("${state.read.formatSize()} / ${state.total.formatSize()}")
							setProgress(100, state.read percentBy state.total, false)
						} else {
							setContentText(state.read.formatSize())
							setProgress(0, 0, true)
						}
					}

					else -> throw IllegalStateException()
				}
			}.build()
		)
	}

	private fun handleDownload() {
		if (currentTask != null) return
		if (tasks.isEmpty() && started) {
			started = false
			@Suppress("DEPRECATION")
			if (SdkCheck.isNougat) stopForeground(STOP_FOREGROUND_REMOVE)
			else stopForeground(true)
			stopSelf()
			return
		}
		if (!started) {
			started = true
			startSelf()
		}
		val task = tasks.removeFirstOrNull() ?: return
		with(stateNotificationBuilder) {
			setWhen(System.currentTimeMillis())
			setContentIntent(createNotificationIntent(task.packageName))
		}
		val connectionState = State.Connecting(task.packageName)
		val partialReleaseFile =
			Cache.getPartialReleaseFile(this, task.release.cacheFileName)
		val job = scope.downloadFile(task, partialReleaseFile)
		currentTask = CurrentTask(task, job, connectionState)
		publishForegroundState(true, connectionState)
		scope.launch {
			_downloadState.emit(State.Connecting(task.packageName))
		}
	}

	private fun createNotificationIntent(packageName: String): PendingIntent? =
		Intent(this, MainActivity::class.java)
			.setAction(Intent.ACTION_VIEW)
			.setData(Uri.parse("package:${packageName}"))
			.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			.getPendingIntent(this)

	private fun CoroutineScope.downloadFile(
		task: Task,
		target: File
	) = launch {
		try {
			val response = downloader.downloadToFile(
				url = task.url,
				target = target,
				headers = { authentication(task.authentication) }
			) { read, total ->
				ensureActive()
				val state = State.Downloading(task.packageName, read, total)
				_downloadState.emit(state)
//				_downloadState.update { lastState ->
//					if (lastState.isStopped() && lastState same state) {
//						lastState
//					} else {
//						publishForegroundState(false, state)
//						state
//					}
//				}
			}

			when (response) {
				is NetworkResponse.Success -> {
					val validationError = validatePackage(task, target)
					if (validationError == null) {
						val releaseFile = Cache.getReleaseFile(
							this@DownloadService,
							task.release.cacheFileName
						)
						target.renameTo(releaseFile)
						publishSuccess(task)
					} else {
						_downloadState.emit(State.Error(task.packageName))
						target.delete()
						showNotificationError(task, ErrorType.Validation(validationError))
					}
				}

				is NetworkResponse.Error -> {
					_downloadState.emit(State.Error(task.packageName))
					val errorType = when (response) {
						is NetworkResponse.Error.ConnectionTimeout -> ErrorType.ConnectionTimeout
						is NetworkResponse.Error.IO -> ErrorType.IO
						is NetworkResponse.Error.SocketTimeout -> ErrorType.SocketTimeout
						else -> ErrorType.Http
					}
					showNotificationError(task, errorType)
				}
			}
		} finally {
			lock.withLock { currentTask = null }
			handleDownload()
		}
	}
}
