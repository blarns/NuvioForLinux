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
 * — violating it deadlocked the app once already.
 *
 * The history, because it explains why this exists at all. QuickJS's JS `fetch` binding used to be
 * synchronous, so it serviced a call with `runBlocking { … }` from inside a native
 * `QuickJs.evaluate` frame — while `httpRequestRaw` begins with `withContext(Dispatchers.IO)`. With
 * plugin execution ALSO on [NuvioBlockingDispatcher] (which is `Dispatchers.IO`, capped at
 * `max(64, nCPU)`), every plugin holding a thread inside `runBlocking` held one of the 64 slots its
 * own HTTP call then needed. Measured, not theorised: 64 of 64 threads parked in
 * `BlockingCoroutine.joinBlocking`, CPU frozen at 1.2s across 125s of wall clock, no log line in
 * 30s. Neither timeout could fire — the fetch bound belonged to a coroutine that was never
 * scheduled, and the plugin bound cannot cancel through a native frame. The stream panel spun
 * forever with nothing logged, which is exactly how SW-01 presented.
 *
 * ⚠ **That root cause is now fixed at source**: the binding is `asyncFunction` and the fetch
 * suspends rather than blocking (matching upstream `NuvioDesktop@72e1cc1`). So this dispatcher is
 * no longer what prevents the deadlock. It is kept for a smaller, still-real reason — plugin and
 * QuickJS CPU work should not compete for the pool the HTTP calls need — and because the isolation
 * invariant above is worth keeping structurally true rather than true by accident.
 *
 * Elastic and idle-collapsing rather than fixed: the real bound is one thread per concurrently
 * executing scraper, which is the user's installed-and-enabled count.
 */
expect val NuvioPluginDispatcher: CoroutineDispatcher
