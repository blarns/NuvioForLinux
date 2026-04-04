package com.nuvio.app.features.plugins

internal expect object PluginStorage {
    fun loadState(profileId: Int): String?
    fun saveState(profileId: Int, payload: String)
}

internal expect fun currentPluginPlatform(): String

internal expect fun currentEpochMillis(): Long
