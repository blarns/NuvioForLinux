package com.nuvio.app.core.ui

// iOS publishes tab titles to a native tab bar; the desktop build has no such surface.
internal actual fun publishNativeTabTitles(
    home: String,
    search: String,
    library: String,
    profile: String,
) {}
