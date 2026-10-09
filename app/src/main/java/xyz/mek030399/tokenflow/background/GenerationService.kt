package xyz.mek030399.tokenflow.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.mek030399.tokenflow.R

/** Implemented by the Application, giving this service the existing coordinator rather than a new job. */
interface GenerationServiceHost {
    val generationCoordinator: GenerationCoordinator
    val generationRuntime: AndroidGenerationRuntime
}

class GenerationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timedOut = false
    private val coordinator: GenerationCoordinator
        get() = (application as GenerationServiceHost).generationCoordinator
    private val runtime: AndroidGenerationRuntime
        get() = (application as GenerationServiceHost).generationRuntime

    override fun onCreate() {
        super.onCreate()
        AndroidGenerationRuntime.ensureChannels(application)
        // Android requires foreground promotion immediately, even if the request has just finished.
        if (!promote(coordinator.snapshots.value.values.filter(GenerationSnapshot::active))) return
        serviceScope.launch {
            coordinator.snapshots.map { snapshots -> snapshots.values.filter(GenerationSnapshot::active) }
                .distinctUntilChangedBy { active -> active.map { it.conversationId } }
                .collect { active ->
                if (timedOut) return@collect
                if (active.isEmpty()) {
                    runtime.onServiceStopped()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else promote(active)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (timedOut) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP_CONVERSATION -> {
                val runId = intent.getLongExtra(EXTRA_RUN_ID, -1)
                if (runId >= 0) coordinator.stopRun(runId)
                else intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let(coordinator::stop)
            }
            ACTION_STOP_ALL -> coordinator.stopAll()
        }
        val active = coordinator.snapshots.value.values.filter(GenerationSnapshot::active)
        if (active.isEmpty()) {
            runtime.onServiceStopped()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        } else promote(active)
        // A killed process recovers stored placeholders as interrupted; it never resends prompts.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits accumulated dataSync foreground work. Retain the already generated text.
        timedOut = true
        coordinator.stopAll()
        runtime.onServiceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        runtime.onServiceStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote(active: List<GenerationSnapshot>): Boolean {
        val first = active.firstOrNull()
        val notification = Notification.Builder(this, AndroidGenerationRuntime.ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.generation_notification_ongoing_title))
            .setContentText(getString(R.string.generation_notification_ongoing_text, active.size.coerceAtLeast(1)))
            .setContentIntent(AndroidGenerationRuntime.conversationPendingIntent(application, first?.conversationId))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .apply {
                first?.let { snapshot ->
                    addAction(Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.generation_notification_stop),
                        stopPendingIntent(ACTION_STOP_CONVERSATION, snapshot.conversationId, snapshot.runId),
                    ).build())
                }
                if (active.size > 1) addAction(Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.generation_notification_stop_all),
                    stopPendingIntent(ACTION_STOP_ALL),
                ).build())
            }.build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ONGOING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ONGOING_NOTIFICATION_ID, notification)
            runtime.onServiceReady()
            true
        } catch (cause: RuntimeException) {
            timedOut = true
            val error = IllegalStateException(getString(R.string.generation_background_unavailable), cause)
            runtime.onServiceFailure(error)
            coordinator.failAll(error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            false
        }
    }

    private fun stopPendingIntent(action: String, conversationId: String? = null, runId: Long? = null): PendingIntent {
        val intent = Intent(this, GenerationService::class.java).apply {
            this.action = action
            data = Uri.Builder().scheme("tokenflow-generation").authority("stop")
                .appendPath(conversationId.orEmpty()).appendPath(runId?.toString().orEmpty()).build()
            conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
            runId?.let { putExtra(EXTRA_RUN_ID, it) }
        }
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "generation_conversation_id"
        private const val EXTRA_RUN_ID = "generation_run_id"
        const val ACTION_OPEN_CONVERSATION = "xyz.mek030399.tokenflow.OPEN_GENERATED_CONVERSATION"
        internal const val ACTION_SYNC = "xyz.mek030399.tokenflow.GENERATION_SYNC"
        internal const val ACTION_STOP_CONVERSATION = "xyz.mek030399.tokenflow.GENERATION_STOP"
        internal const val ACTION_STOP_ALL = "xyz.mek030399.tokenflow.GENERATION_STOP_ALL"
        private const val ONGOING_NOTIFICATION_ID = 101
    }
}
