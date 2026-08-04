package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val NuvioBlockingDispatcher: CoroutineDispatcher = Dispatchers.IO
