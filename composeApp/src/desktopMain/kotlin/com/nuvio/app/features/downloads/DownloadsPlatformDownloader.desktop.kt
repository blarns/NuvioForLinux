package com.nuvio.app.features.downloads

internal actual object DownloadsPlatformDownloader {
    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle = object : DownloadsTaskHandle { override fun cancel() {} }

    actual fun removeFile(localFileUri: String?): Boolean = true
    actual fun removePartialFile(destinationFileName: String): Boolean = true
    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = null
}
