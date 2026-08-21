package com.nuvio.app.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for work that blocks its thread — the stream fan-outs and the HTTP calls under them.
 *
 * This must not be [kotlinx.coroutines.Dispatchers.Default]. That pool is sized to the
 * CPU count, and a scraper blocking one thread per in-flight HTTP request can consume
 * every thread in it — including the one the stream fan-in needs to publish results —
 * which leaves the source panel spinning forever with no error. A blocked thread also
 * never reaches a cancellation point, so the plugin timeout cannot recover it.
 */
expect val NuvioBlockingDispatcher: CoroutineDispatcher

/**
 * Dispatcher for QuickJS plugin execution, and ONLY for that.
 *
 * ⚠ **The rule: nothing dispatched here may block waiting on work that dispatches back here, and
 * this pool must stay disjoint from the one `httpRequestRaw` uses.** It is not a style preference
 * — violating it deadlocks the app, and it has already happened.
 *
 * The JS `fetch` binding QuickJS exposes to scraper code is synchronous, so it services a call
 * with `runBlocking { … }` from inside a native `QuickJs.evaluate` frame — and `httpRequestRaw`
 * begins with `withContext(Dispatchers.IO)`. When plugin execution ALSO ran on
 * [NuvioBlockingDispatcher] (which is `Dispatchers.IO`, capped at `max(64, nCPU)` = 64 threads
 * here), every plugin holding a thread inside `runBlocking` was holding one of the 64 slots its
 * own HTTP call then needed. With ~20 scrapers each making several concurrent fetches, all 64
 * filled and the fetches queued behind the threads waiting for them.
 *
 * Measured, not theorised: 64 of 64 threads parked in `BlockingCoroutine.joinBlocking`, CPU time
 * frozen at 1.2s across 125s of wall clock, no new log line in 30s. Neither timeout could fire —
 * the 20s fetch bound belongs to a coroutine that was never scheduled, and the 60s plugin bound
 * cannot cancel through a native frame. So the panel spun with nothing logged, which is exactly
 * how SW-01 presented: "Finding streams…" forever and not one `Timed out:` line.
 *
 * Elastic and idle-collapsing rather than fixed: the real bound is one thread per concurrently
 * executing scraper, which is the user's installed-and-enabled count.
 */
expect val NuvioPluginDispatcher: CoroutineDispatcher
