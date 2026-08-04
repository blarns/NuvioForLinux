package com.nuvio.app.features.player

/**
 * Holds a URL/magnet passed to the app on launch (CLI arg or browser "open with"), so the
 * common UI can pick it up once a profile is active and start playback. Set by the desktop
 * entry point; consumed once by App. Inert on mobile (nothing sets it).
 */
object ExternalOpenRequestStore {
    enum class Kind { HTTP, MAGNET }

    data class Request(
        val kind: Kind,
        val url: String,
        val title: String,
        val infoHash: String? = null,
    )

    @Volatile
    private var pending: Request? = null

    fun set(request: Request) {
        pending = request
    }

    /** Returns the pending request once, then clears it. */
    fun consume(): Request? {
        val request = pending
        pending = null
        return request
    }
}
