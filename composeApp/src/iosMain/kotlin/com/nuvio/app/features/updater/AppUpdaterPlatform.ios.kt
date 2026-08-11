package com.nuvio.app.features.updater

import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_not_available
import org.jetbrains.compose.resources.getString

actual object AppUpdaterPlatform {
    actual val isSupported: Boolean = false
    actual val isDebugBuild: Boolean = false

    actual fun getSupportedAbis(): List<String> = emptyList()

    actual fun getIgnoredTag(): String? = null

    actual fun setIgnoredTag(tag: String?) = Unit

    actual val supportsExperimentalChannel: Boolean = false

    actual fun getExperimentalUpdatesEnabled(): Boolean = false

    actual fun setExperimentalUpdatesEnabled(enabled: Boolean) = Unit

    actual val supportsDataBackup: Boolean = false

    actual suspend fun backupUserData(): Result<String> =
        Result.failure(IllegalStateException("Data backup is only available on desktop."))

    actual fun pickBackupFile(): String? = null

    actual suspend fun stageDataRestore(zipPath: String): Result<Int> =
        Result.failure(IllegalStateException("Data restore is only available on desktop."))

    actual fun requestQuit() = Unit

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = Result.failure(IllegalStateException(getString(Res.string.updates_not_available)))

    actual fun canRequestPackageInstalls(): Boolean = false

    actual fun openUnknownSourcesSettings() = Unit

    actual fun installDownloadedApk(path: String): Result<Unit> =
        Result.failure(IllegalStateException(runBlocking { getString(Res.string.updates_not_available) }))
}
