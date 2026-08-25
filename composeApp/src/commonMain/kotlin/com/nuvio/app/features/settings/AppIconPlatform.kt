package com.nuvio.app.features.settings

internal expect object AppIconPlatform {
    val isSupported: Boolean
    val requiresCloseConfirmation: Boolean
    fun currentIconName(): String?
    fun currentBlackBackground(): Boolean
    fun setBlackBackground(enabled: Boolean): Boolean
    suspend fun activateIcon(name: String?): Boolean
}
