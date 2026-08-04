package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Left on the default pool: iOS keeps its existing behaviour, since this fork only
// builds and verifies the desktop target and cannot test a dispatcher change there.
actual val NuvioBlockingDispatcher: CoroutineDispatcher = Dispatchers.Default
