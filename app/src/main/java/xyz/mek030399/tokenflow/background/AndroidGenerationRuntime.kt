package xyz.mek030399.tokenflow.background

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import xyz.mek030399.tokenflow.MainActivity
import xyz.mek030399.tokenflow.R

/** Register eagerly, before the first Activity starts, so service startup observes real visibility. */
class AndroidGenerationRuntime(private val application: Application) : GenerationLifecycle {
    private val startedActivities = mutableSetOf<Activity>()
    private var serviceReady = CompletableDeferred<Unit>()
    private var serviceRunning = false
    val appVisible: Boolean get() = synchronized(startedActivities) { startedActivities.isNotEmpty() }

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                synchronized(startedActivities) { startedActivities.add(activity) }
            }
            override fun onActivityStopped(activity: Activity) {
                synchronized(startedActivities) { startedActivities.remove(activity) }
            }
            override fun onActivityDestroyed(activity: Activity) {
                synchronized(startedActivities) { startedActivities.remove(activity) }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        })
        ensureChannels(application)
    }

    override fun onStarting(snapshot: GenerationSnapshot) {
        check(appVisible) { application.getString(R.string.generation_background_unavailable) }
        if (!serviceRunning && serviceReady.isCompleted) serviceReady = CompletableDeferred()
        try {
            application.startForegroundService(Intent(application, GenerationService::class.java).apply {
                action = GenerationService.ACTION_SYNC
            })
        } catch (error: RuntimeException) {
            throw IllegalStateException(application.getString(R.string.generation_background_unavailable), error)
        }
    }

    override suspend fun awaitReady(snapshot: GenerationSnapshot) {
        try {
            withTimeout(10_000) { serviceReady.await() }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException(application.getString(R.string.generation_background_unavailable), error)
        }
    }

    internal fun onServiceReady() {
        serviceRunning = true
        serviceReady.complete(Unit)
    }

    internal fun onServiceFailure(error: Throwable) {
        serviceRunning = false
        serviceReady.completeExceptionally(error)
    }

    internal fun onServiceStopped() { serviceRunning = false }

    override fun onFinished(snapshot: GenerationSnapshot) {
        if (appVisible || snapshot.status !in setOf(GenerationStatus.SUCCEEDED, GenerationStatus.FAILED)) return
        if (Build.VERSION.SDK_INT >= 33 && application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val manager = application.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val title = application.getString(
            if (snapshot.status == GenerationStatus.SUCCEEDED) R.string.generation_notification_completed_title
            else R.string.generation_notification_failed_title,
        )
        val builder = Notification.Builder(application, COMPLETED_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(application.getString(R.string.generation_notification_finished_text))
            .setContentIntent(conversationPendingIntent(application, snapshot.conversationId))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_STATUS)
        // Both the ordinary notification and its lockscreen version contain only generic status.
        builder.setPublicVersion(Notification.Builder(application, COMPLETED_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(application.getString(R.string.generation_notification_finished_text))
            .build())
        manager.notify("generation:${snapshot.conversationId}", COMPLETED_NOTIFICATION_ID, builder.build())
    }

    companion object {
        internal const val ONGOING_CHANNEL = "generation_ongoing"
        internal const val COMPLETED_CHANNEL = "generation_completed"
        private const val COMPLETED_NOTIFICATION_ID = 102

        internal fun ensureChannels(application: Application) {
            application.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel(
                    ONGOING_CHANNEL,
                    application.getString(R.string.generation_channel_ongoing_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
                NotificationChannel(
                    COMPLETED_CHANNEL,
                    application.getString(R.string.generation_channel_completed_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ))
        }

        internal fun conversationPendingIntent(application: Application, conversationId: String?): PendingIntent {
            val intent = Intent(application, MainActivity::class.java).apply {
                action = GenerationService.ACTION_OPEN_CONVERSATION
                data = Uri.Builder().scheme("tokenflow").authority("conversation")
                    .appendPath(conversationId.orEmpty()).build()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                conversationId?.let { putExtra(GenerationService.EXTRA_CONVERSATION_ID, it) }
            }
            return PendingIntent.getActivity(application, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
