package com.nuvio.app.core.auth

internal actual fun currentDeviceClientMetadata(): DeviceClientMetadata = DeviceClientMetadata(
    deviceName = runCatching { java.net.InetAddress.getLocalHost().hostName }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "Linux desktop",
    platform = "linux",
)
