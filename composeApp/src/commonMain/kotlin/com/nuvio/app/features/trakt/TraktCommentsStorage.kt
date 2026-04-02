package com.nuvio.app.features.trakt

internal expect object TraktCommentsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
}
