package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for work that blocks its thread — plugin (QuickJS) execution and the
 * synchronous `fetch` bridge it exposes to scraper code.
 *
 * This must not be [kotlinx.coroutines.Dispatchers.Default]. That pool is sized to the
 * CPU count, and a scraper blocking one thread per in-flight HTTP request can consume
 * every thread in it — including the one the stream fan-in needs to publish results —
 * which leaves the source panel spinning forever with no error. A blocked thread also
 * never reaches a cancellation point, so the plugin timeout cannot recover it.
 */
expect val NuvioBlockingDispatcher: CoroutineDispatcher
