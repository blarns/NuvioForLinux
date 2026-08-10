package com.nuvio.app.features.updater

expect object AppUpdaterPlatform {
    val isSupported: Boolean

    fun getSupportedAbis(): List<String>

    fun getIgnoredTag(): String?

    fun setIgnoredTag(tag: String?)

    // Fork-only: opt-in to the experimental (alpha) release channel. Off by default, so a
    // GitHub pre-release is invisible to everyone who has not deliberately turned this on.
    val supportsExperimentalChannel: Boolean

    fun getExperimentalUpdatesEnabled(): Boolean

    fun setExperimentalUpdatesEnabled(enabled: Boolean)

    suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    fun canRequestPackageInstalls(): Boolean

    fun openUnknownSourcesSettings()

    fun installDownloadedApk(path: String): Result<Unit>
}