package com.looker.droidify.index

import android.content.Context
import android.net.Uri
import com.looker.core.common.cache.Cache
import com.looker.core.common.extension.fingerprint
import com.looker.core.common.result.Result
import com.looker.core.model.Product
import com.looker.core.model.Release
import com.looker.core.model.Repository
import com.looker.droidify.database.Database
import com.looker.droidify.utility.extension.android.Android
import com.looker.droidify.utility.getProgress
import com.looker.network.Downloader
import com.looker.network.NetworkResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.internal.http.toHttpDateString
import java.io.File
import java.security.cert.X509Certificate
import java.util.jar.JarEntry
import java.util.jar.JarFile

object RepositoryUpdater {
	enum class Stage {
		DOWNLOAD, PROCESS, MERGE, COMMIT
	}

	// TODO Add support for Index-V2 and also cleanup everything here
	private enum class IndexType(
		val jarName: String,
		val contentName: String,
	) {
		INDEX_V1("index-v1.jar", "index-v1.json")
	}

	enum class ErrorType {
		NETWORK, HTTP, VALIDATION, PARSING
	}

	class UpdateException : Exception {
		val errorType: ErrorType

		constructor(errorType: ErrorType, message: String) : super(message) {
			this.errorType = errorType
		}

		constructor(errorType: ErrorType, message: String, cause: Exception) : super(
			message,
			cause
		) {
			this.errorType = errorType
		}
	}

	private val updaterLock = Any()
	private val cleanupLock = Any()

	private lateinit var downloader: Downloader

	fun init(scope: CoroutineScope, downloader: Downloader) {
		this.downloader = downloader
		var lastDisabled = setOf<Long>()
		Database.RepositoryAdapter
			.getAllRemovedStream()
			.onEach { deletedRepos ->
				val newDisabled =
					deletedRepos.asSequence().filter { !it.second }.map { it.first }.toSet()
				val disabled = newDisabled - lastDisabled
				lastDisabled = newDisabled
				val deleted =
					deletedRepos.asSequence().filter { it.second }.map { it.first }.toSet()
				if (disabled.isNotEmpty() || deleted.isNotEmpty()) {
					val pairs = (disabled.asSequence().map { Pair(it, false) } +
							deleted.asSequence().map { Pair(it, true) }).toSet()
					synchronized(cleanupLock) { Database.RepositoryAdapter.cleanup(pairs) }
				}
			}
			.launchIn(scope + Dispatchers.IO)
	}

	fun await() {
		synchronized(updaterLock) { }
	}

	suspend fun update(
		context: Context,
		repository: Repository,
		unstable: Boolean,
		callback: (Stage, Long, Long?) -> Unit
	) = update(
		context = context,
		repository = repository,
		unstable = unstable,
		indexTypes = listOf(IndexType.INDEX_V1),
		callback = callback
	)

	private suspend fun update(
		context: Context,
		repository: Repository,
		unstable: Boolean,
		indexTypes: List<IndexType>,
		callback: (Stage, Long, Long?) -> Unit
	): Result<Boolean> = withContext(Dispatchers.IO) {
		val indexType = indexTypes[0]
		when (val request = downloadIndex(context, repository, indexType, callback)) {
			is Result.Error -> {
				val result = request.data
					?: return@withContext Result.Error(request.exception, false)

				val file = request.data?.file
					?: return@withContext Result.Error(request.exception, false)
				file.delete()
				if (result.statusCode == 404 && indexTypes.isNotEmpty()) {
					update(
						context = context,
						repository = repository,
						indexTypes = indexTypes.subList(1, indexTypes.size),
						unstable = unstable,
						callback = callback
					)
				} else {
					Result.Error(
						UpdateException(
							ErrorType.HTTP,
							"Invalid response: HTTP ${result.statusCode}"
						)
					)
				}
			}

			is Result.Success -> {
				if (request.data.isUnmodified) {
					request.data.file.delete()
					Result.Success(false)
				} else {
					try {
						val isFileParsedSuccessfully = processFile(
							context = context,
							repository = repository,
							indexType = indexType,
							unstable = unstable,
							file = request.data.file,
							lastModified = request.data.lastModified,
							entityTag = request.data.entityTag,
							callback = callback
						)
						Result.Success(isFileParsedSuccessfully)
					} catch (e: UpdateException) {
						Result.Error(e)
					}
				}
			}
		}
	}

	private suspend fun downloadIndex(
		context: Context,
		repository: Repository,
		indexType: IndexType,
		callback: (Stage, Long, Long?) -> Unit
	): Result<IndexFile> = withContext(Dispatchers.IO) {
		val file = Cache.getTemporaryFile(context)
		val result = downloader.downloadToFile(
			url = Uri.parse(repository.address).buildUpon()
				.appendPath(indexType.jarName).build().toString(),
			target = file,
			headers = {
				ifModifiedSince(repository.lastModified)
				etag(repository.entityTag)
				authentication(repository.authentication)
			}
		) { read, total ->
			callback(Stage.DOWNLOAD, read, total)
		}

		when (result) {
			is NetworkResponse.Success -> {
				Result.Success(
					IndexFile(
						isUnmodified = result.statusCode == 304,
						lastModified = result.lastModified?.toHttpDateString() ?: "",
						entityTag = result.etag ?: "",
						statusCode = result.statusCode,
						file = file
					)
				)
			}

			is NetworkResponse.Error -> {
				file.delete()
				when(result) {
					is NetworkResponse.Error.Http -> {
						val errorType = if (result.statusCode in 400..499) ErrorType.HTTP
						else ErrorType.NETWORK
						Result.Error(UpdateException(
							errorType = errorType,
							message = "Failed with Status: ${result.statusCode}"
						))
					}
					is NetworkResponse.Error.ConnectionTimeout -> Result.Error(result.exception)
					is NetworkResponse.Error.IO -> Result.Error(result.exception)
					is NetworkResponse.Error.SocketTimeout -> Result.Error(result.exception)
					is NetworkResponse.Error.Unknown -> Result.Error(result.exception)
				}
			}
		}
	}

	private fun processFile(
		context: Context,
		repository: Repository, indexType: IndexType, unstable: Boolean,
		file: File, lastModified: String, entityTag: String, callback: (Stage, Long, Long?) -> Unit,
	): Boolean {
		var rollback = true
		return synchronized(updaterLock) {
			try {
				val jarFile = JarFile(file, true)
				val indexEntry = jarFile.getEntry(indexType.contentName) as JarEntry
				val total = indexEntry.size
				Database.UpdaterAdapter.createTemporaryTable()
				val features = context.packageManager.systemAvailableFeatures
					.asSequence().map { it.name }.toSet() + setOf("android.hardware.touchscreen")


				var changedRepositoryVar: Repository? = null

				val mergerFile = Cache.getTemporaryFile(context)
				try {
					val unmergedProducts = mutableListOf<Product>()
					val unmergedReleases = mutableListOf<Pair<String, List<Release>>>()
					IndexMerger(mergerFile).use { indexMerger ->
						jarFile.getInputStream(indexEntry).getProgress {
							callback(Stage.PROCESS, it, total)
						}.use { entryStream ->
							IndexV1Parser.parse(
								repository.id,
								entryStream,
								object : IndexV1Parser.Callback {
									override fun onRepository(
										mirrors: List<String>,
										name: String,
										description: String,
										version: Int,
										timestamp: Long,
									) {
										changedRepositoryVar = repository.update(
											mirrors, name, description, version,
											lastModified, entityTag, timestamp
										)
									}

									override fun onProduct(product: Product) {
										if (Thread.interrupted()) {
											throw InterruptedException()
										}
										unmergedProducts += product
										if (unmergedProducts.size >= 50) {
											indexMerger.addProducts(unmergedProducts)
											unmergedProducts.clear()
										}
									}

									override fun onReleases(
										packageName: String,
										releases: List<Release>,
									) {
										if (Thread.interrupted()) {
											throw InterruptedException()
										}
										unmergedReleases += Pair(packageName, releases)
										if (unmergedReleases.size >= 50) {
											indexMerger.addReleases(unmergedReleases)
											unmergedReleases.clear()
										}
									}
								})

							if (Thread.interrupted()) {
								throw InterruptedException()
							}
							if (unmergedProducts.isNotEmpty()) {
								indexMerger.addProducts(unmergedProducts)
								unmergedProducts.clear()
							}
							if (unmergedReleases.isNotEmpty()) {
								indexMerger.addReleases(unmergedReleases)
								unmergedReleases.clear()
							}
							var progress = 0
							indexMerger.forEach(repository.id, 50) { products, totalCount ->
								if (Thread.interrupted()) {
									throw InterruptedException()
								}
								progress += products.size
								callback(
									Stage.MERGE,
									progress.toLong(),
									totalCount.toLong()
								)
								Database.UpdaterAdapter.putTemporary(products
									.map { transformProduct(it, features, unstable) })
							}
						}
					}
				} finally {
					mergerFile.delete()
				}

				val changedRepository = changedRepositoryVar

				val workRepository = changedRepository ?: repository
				if (workRepository.timestamp < repository.timestamp) {
					throw UpdateException(
						ErrorType.VALIDATION, "New index is older than current index: " +
								"${workRepository.timestamp} < ${repository.timestamp}"
					)
				} else {
					val fingerprint = run {
						val certificateFromJar = run {
							val codeSigners = indexEntry.codeSigners
							if (codeSigners == null || codeSigners.size != 1) {
								throw UpdateException(
									ErrorType.VALIDATION,
									"index.jar must be signed by a single code signer"
								)
							} else {
								val certificates =
									codeSigners[0].signerCertPath?.certificates.orEmpty()
								if (certificates.size != 1) {
									throw UpdateException(
										ErrorType.VALIDATION,
										"index.jar code signer should have only one certificate"
									)
								} else {
									certificates[0] as X509Certificate
								}
							}
						}
						val fingerprintFromJar = certificateFromJar.fingerprint()
						fingerprintFromJar.uppercase()
					}

					val commitRepository = if (workRepository.fingerprint != fingerprint) {
						if (workRepository.fingerprint.isEmpty()) {
							workRepository.copy(fingerprint = fingerprint)
						} else {
							throw UpdateException(
								ErrorType.VALIDATION,
								"Certificate fingerprints do not match"
							)
						}
					} else {
						workRepository
					}
					if (Thread.interrupted()) {
						throw InterruptedException()
					}
					callback(Stage.COMMIT, 0, null)
					synchronized(cleanupLock) {
						Database.UpdaterAdapter.finishTemporary(
							commitRepository,
							true
						)
					}
					rollback = false
					true
				}
			} catch (e: Exception) {
				throw when (e) {
					is UpdateException, is InterruptedException -> e
					else -> UpdateException(ErrorType.PARSING, "Error parsing index", e)
				}
			} finally {
				file.delete()
				if (rollback) {
					Database.UpdaterAdapter.finishTemporary(repository, false)
				}
			}
		}
	}

	private fun transformProduct(
		product: Product,
		features: Set<String>,
		unstable: Boolean,
	): Product {
		val releasePairs =
			product.releases.distinctBy { it.identifier }.sortedByDescending { it.versionCode }
				.map { it ->
					val incompatibilities = mutableListOf<Release.Incompatibility>()
					if (it.minSdkVersion > 0 && Android.sdk < it.minSdkVersion) {
						incompatibilities += Release.Incompatibility.MinSdk
					}
					if (it.maxSdkVersion > 0 && Android.sdk > it.maxSdkVersion) {
						incompatibilities += Release.Incompatibility.MaxSdk
					}
					if (it.platforms.isNotEmpty() && it.platforms.intersect(Android.platforms)
							.isEmpty()
					) {
						incompatibilities += Release.Incompatibility.Platform
					}
					incompatibilities += (it.features - features).sorted()
						.map { Release.Incompatibility.Feature(it) }
					Pair(it, incompatibilities as List<Release.Incompatibility>)
				}.toMutableList()

		val predicate: (Release) -> Boolean = {
			unstable || product.suggestedVersionCode <= 0 ||
					it.versionCode <= product.suggestedVersionCode
		}
		val firstCompatibleReleaseIndex =
			releasePairs.indexOfFirst { it.second.isEmpty() && predicate(it.first) }
		val firstReleaseIndex =
			if (firstCompatibleReleaseIndex >= 0) firstCompatibleReleaseIndex else
				releasePairs.indexOfFirst { predicate(it.first) }
		val firstSelected = if (firstReleaseIndex >= 0) releasePairs[firstReleaseIndex] else null

		val releases = releasePairs.map { (release, incompatibilities) ->
			release
				.copy(incompatibilities = incompatibilities, selected = firstSelected
					?.let { it.first.versionCode == release.versionCode && it.second == incompatibilities } == true)
		}
		return product.copy(releases = releases)
	}
}

data class IndexFile(
	val isUnmodified: Boolean,
	val lastModified: String,
	val entityTag: String,
	val statusCode: Int,
	val file: File
)