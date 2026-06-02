package com.nuvio.app.features.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

internal actual object EpisodeReleaseNotificationPlatform {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scheduledJobs = mutableMapOf<String, Job>()

    actual suspend fun notificationsAuthorized(): Boolean {
        return runCatching {
            ProcessBuilder("which", "notify-send").start().waitFor() == 0
        }.getOrDefault(false)
    }

    actual suspend fun requestAuthorization(): Boolean = notificationsAuthorized()

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) {
        runCatching {
            ProcessBuilder(
                "notify-send",
                request.notificationTitle,
                request.notificationBody,
                "--app-name=Nuvio",
                "--urgency=normal",
            ).start()
        }
    }

    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {
        for (request in requests) {
            val releaseDateTime = runCatching {
                LocalDateTime.parse(request.releaseDateIso)
            }.getOrNull() ?: continue

            val now = LocalDateTime.now()
            val delayMs = ChronoUnit.MILLIS.between(now, releaseDateTime)

            if (delayMs <= 0) continue

            val job = scope.launch {
                delay(delayMs)
                runCatching {
                    ProcessBuilder(
                        "notify-send",
                        request.notificationTitle,
                        request.notificationBody,
                        "--app-name=Nuvio",
                        "--urgency=normal",
                    ).start()
                }
            }

            scheduledJobs[request.requestId]?.cancel()
            scheduledJobs[request.requestId] = job
        }
    }

    actual suspend fun clearScheduledEpisodeReleaseNotifications() {
        scheduledJobs.values.forEach { it.cancel() }
        scheduledJobs.clear()
    }
}
