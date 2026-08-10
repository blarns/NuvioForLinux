package com.nuvio.app.features.updater

expect object AppUpdaterPlatform {
    val isSupported: Boolean
    val isDebugBuild: Boolean

    fun getSupportedAbis(): List<String>

    fun getIgnoredTag(): String?

    fun setIgnoredTag(tag: String?)

    // Fork-only: opt-in to the experimental (alpha) release channel. Off by default, so a
    // GitHub pre-release is invisible to everyone who has not deliberately turned this on.
    val supportsExperimentalChannel: Boolean

    fun getExperimentalUpdatesEnabled(): Boolean

    fun setExperimentalUpdatesEnabled(enabled: Boolean)

    // Fork-only: copy the app's data directory somewhere safe before opting into alphas.
    // Backup only — restoring is a manual "quit Nuvio and unzip this over your data dir",
    // because overwriting storage under a running app is a footgun.
    val supportsDataBackup: Boolean

    suspend fun backupUserData(): Result<String>

    suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    fun canRequestPackageInstalls(): Boolean

    fun openUnknownSourcesSettings()

    fun installDownloadedApk(path: String): Result<Unit>
}
