package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

actual val NuvioBlockingDispatcher: CoroutineDispatcher = Dispatchers.IO

// See the expect declaration: plugin execution must not share a pool with the HTTP calls its
// synchronous fetch bridge makes, or the two starve each other.
private val pluginThreadCount = AtomicInteger(0)

actual val NuvioPluginDispatcher: CoroutineDispatcher =
    Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nuvio-plugin-${pluginThreadCount.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
