package com.nuvio.app.features.notifications

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nuvio.app.MainActivity
import com.nuvio.app.R
import kotlin.math.abs

class EpisodeReleaseNotificationWorker(
    appContext: android.content.Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        if (!EpisodeReleaseNotificationPlatform.notificationsAuthorized()) {
            return Result.success()
        }

        val requestId = inputData.getString(EpisodeReleaseNotificationPlatform.workerRequestIdKey)
            ?: return Result.failure()
        val title = inputData.getString(EpisodeReleaseNotificationPlatform.workerTitleKey)
            ?: return Result.failure()
        val body = inputData.getString(EpisodeReleaseNotificationPlatform.workerBodyKey)
            ?: return Result.failure()
        val deepLink = inputData.getString(EpisodeReleaseNotificationPlatform.workerDeepLinkKey)
            ?: return Result.failure()

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(deepLink)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            abs(requestId.hashCode()),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, EpisodeReleaseNotificationPlatform.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(abs(requestId.hashCode()), notification)

        return Result.success()
    }
}