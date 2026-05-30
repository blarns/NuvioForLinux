package com.nuvio.app.features.notifications

internal actual object EpisodeReleaseNotificationPlatform {
    actual suspend fun notificationsAuthorized(): Boolean = false
    actual suspend fun requestAuthorization(): Boolean = false
    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {}
    actual suspend fun clearScheduledEpisodeReleaseNotifications() {}
    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) {}
}
