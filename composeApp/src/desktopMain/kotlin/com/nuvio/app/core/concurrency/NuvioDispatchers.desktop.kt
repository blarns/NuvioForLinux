package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

actual val NuvioBlockingDispatcher: CoroutineDispatcher = Dispatchers.IO

// A cached pool: a thread per concurrently executing scraper, reaped after 60s idle. Daemon
// threads so a wedged plugin can never hold the JVM open at quit. See the expect declaration
// for why this must not be Dispatchers.IO.
private val pluginThreadCount = AtomicInteger(0)

actual val NuvioPluginDispatcher: CoroutineDispatcher =
    Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nuvio-plugin-${pluginThreadCount.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
