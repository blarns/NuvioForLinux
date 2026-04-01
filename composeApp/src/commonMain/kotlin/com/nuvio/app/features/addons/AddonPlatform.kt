package com.nuvio.app.features.addons

internal expect object AddonStorage {
    fun loadInstalledAddonUrls(profileId: Int): List<String>
    fun saveInstalledAddonUrls(profileId: Int, urls: List<String>)
}

expect suspend fun httpGetText(url: String): String

expect suspend fun httpPostJson(url: String, body: String): String
