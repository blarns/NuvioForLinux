package com.nuvio.app.features.notifications

import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal actual object EpisodeReleaseNotificationPlatform {
    private const val scheduledIdsKey = "episode_release_notification_scheduled_ids"

    actual suspend fun notificationsAuthorized(): Boolean = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            continuation.resume(
                status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional,
            )
        }
    }

    actual suspend fun requestAuthorization(): Boolean = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, _ ->
            continuation.resume(granted)
        }
    }

    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {
        clearScheduledEpisodeReleaseNotifications()

        val center = UNUserNotificationCenter.currentNotificationCenter()
        val scheduledIds = mutableListOf<String>()

        requests.forEach { request ->
            val dateComponents = buildDateComponents(request.releaseDateIso) ?: return@forEach
            val scheduledDate = NSCalendar.currentCalendar.dateFromComponents(dateComponents) ?: return@forEach
            if (scheduledDate.timeIntervalSince1970 <= NSDate().timeIntervalSince1970) return@forEach

            val content = UNMutableNotificationContent().apply {
                setTitle(request.notificationTitle)
                setBody(request.notificationBody)
                setUserInfo(mapOf("deeplink" to request.deepLinkUrl))
            }
            val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
                dateComponents = dateComponents,
                repeats = false,
            )
            val notificationRequest = UNNotificationRequest.requestWithIdentifier(
                identifier = request.requestId,
                content = content,
                trigger = trigger,
            )
            center.addNotificationRequest(notificationRequest) { _ -> }
            scheduledIds += request.requestId
        }

        NSUserDefaults.standardUserDefaults.setObject(
            scheduledIds.joinToString(separator = "|"),
            forKey = ProfileScopedKey.of(scheduledIdsKey),
        )
    }

    actual suspend fun clearScheduledEpisodeReleaseNotifications() {
        val identifiers = trackedScheduledIds()
        if (identifiers.isNotEmpty()) {
            UNUserNotificationCenter.currentNotificationCenter()
                .removePendingNotificationRequestsWithIdentifiers(identifiers)
        }
        NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(scheduledIdsKey))
    }

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) {
        val content = UNMutableNotificationContent().apply {
            setTitle(request.notificationTitle)
            setBody(request.notificationBody)
            setUserInfo(mapOf("deeplink" to request.deepLinkUrl))
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = 1.0,
            repeats = false,
        )
        val notificationRequest = UNNotificationRequest.requestWithIdentifier(
            identifier = request.requestId,
            content = content,
            trigger = trigger,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(notificationRequest) { _ -> }
    }

    private fun trackedScheduledIds(): List<String> =
        NSUserDefaults.standardUserDefaults
            .stringForKey(ProfileScopedKey.of(scheduledIdsKey))
            ?.split('|')
            ?.filter { value -> value.isNotBlank() }
            .orEmpty()

    private fun buildDateComponents(releaseDateIso: String): NSDateComponents? {
        val parts = releaseDateIso.split('-')
        if (parts.size != 3) return null

        val year = parts[0].toLongOrNull() ?: return null
        val month = parts[1].toLongOrNull() ?: return null
        val day = parts[2].toLongOrNull() ?: return null

        return NSDateComponents().apply {
            this.year = year
            this.month = month
            this.day = day
            this.hour = EpisodeReleaseNotificationHour.toLong()
            this.minute = EpisodeReleaseNotificationMinute.toLong()
            this.second = 0
            this.calendar = NSCalendar.currentCalendar
            setTimeZone(NSCalendar.currentCalendar.timeZone)
        }
    }
}