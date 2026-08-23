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

/**
 * One private daemon thread, shut down as soon as the runtime is done with it. See the expect
 * declaration: a QuickJS runtime must never be driven from two threads.
 */
actual suspend fun <T> withPluginRuntimeThread(
    name: String,
    block: suspend (CoroutineDispatcher) -> T,
): T {
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
    return try {
        block(executor.asCoroutineDispatcher())
    } finally {
        executor.shutdown()
    }
}
