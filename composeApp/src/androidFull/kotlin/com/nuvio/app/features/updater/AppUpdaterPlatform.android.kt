package com.nuvio.app.features.updater

actual object AppUpdaterPlatform {
    actual val isSupported: Boolean = true
    actual val isDebugBuild: Boolean
        get() = AndroidAppUpdaterPlatform.isDebugBuild()

    actual fun getSupportedAbis(): List<String> = AndroidAppUpdaterPlatform.getSupportedAbis()

    actual fun getIgnoredTag(): String? = AndroidAppUpdaterPlatform.getIgnoredTag()

    actual fun setIgnoredTag(tag: String?) {
        AndroidAppUpdaterPlatform.setIgnoredTag(tag)
    }

    actual val supportsExperimentalChannel: Boolean = true

    actual fun getExperimentalUpdatesEnabled(): Boolean =
        AndroidAppUpdaterPlatform.getExperimentalUpdatesEnabled()

    actual fun setExperimentalUpdatesEnabled(enabled: Boolean) {
        AndroidAppUpdaterPlatform.setExperimentalUpdatesEnabled(enabled)
    }

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
    ): Result<String> = AndroidAppUpdaterPlatform.downloadApk(assetUrl, assetName, onProgress)

    actual fun canRequestPackageInstalls(): Boolean = AndroidAppUpdaterPlatform.canRequestPackageInstalls()

    actual fun openUnknownSourcesSettings() {
        AndroidAppUpdaterPlatform.openUnknownSourcesSettings()
    }

    actual fun installDownloadedApk(path: String): Result<Unit> = AndroidAppUpdaterPlatform.installDownloadedApk(path)
}
