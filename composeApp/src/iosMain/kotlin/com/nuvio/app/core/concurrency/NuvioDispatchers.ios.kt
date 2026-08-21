package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Left on the default pool: iOS keeps its existing behaviour, since this fork only
// builds and verifies the desktop target and cannot test a dispatcher change there.
actual val NuvioBlockingDispatcher: CoroutineDispatcher = Dispatchers.Default

// Likewise unchanged. iOS does not run the plugin runtime in this fork, and giving it a
// dispatcher that has never been exercised there would be a guess, not a fix.
actual val NuvioPluginDispatcher: CoroutineDispatcher = Dispatchers.Default
