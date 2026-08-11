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

    // Fork-only: copy the app's data directory somewhere safe before opting into alphas, and
    // put it back if an alpha (or the downgrade off one) leaves the app unable to read it.
    val supportsDataBackup: Boolean

    suspend fun backupUserData(): Result<String>

    /** Native "open file" dialog for a backup zip. Null when the user cancels. */
    fun pickBackupFile(): String?

    /**
     * Unpack a backup and queue it for the next launch, returning how many files were staged.
     * Deliberately not applied live — see `DesktopRestore`.
     */
    suspend fun stageDataRestore(zipPath: String): Result<Int>

    /** Quit the app through the normal shutdown path, so a staged restore takes effect. */
    fun requestQuit()

    suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    fun canRequestPackageInstalls(): Boolean

    fun openUnknownSourcesSettings()

    fun installDownloadedApk(path: String): Result<Unit>
}
