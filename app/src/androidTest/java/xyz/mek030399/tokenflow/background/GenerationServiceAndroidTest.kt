package xyz.mek030399.tokenflow.background

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.mek030399.tokenflow.MainActivity
import xyz.mek030399.tokenflow.TokenFlowApplication
import xyz.mek030399.tokenflow.data.ChatEvent
import xyz.mek030399.tokenflow.data.ChatMessage
import xyz.mek030399.tokenflow.data.Usage
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Run only on the disposable instrumentation AVD; requests here never touch a provider. */
@RunWith(AndroidJUnit4::class)
class GenerationServiceAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as TokenFlowApplication
    private val coordinator get() = application.generationCoordinator
    private val notificationManager get() = application.getSystemService(NotificationManager::class.java)
    private val ids = mutableListOf<String>()

    @Before
    fun allowNotificationsOnTheDisposableTestApp() {
        if (Build.VERSION.SDK_INT >= 33 && application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${application.packageName} android.permission.POST_NOTIFICATIONS",
            ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        }
    }

    @After
    fun stopOnlyOwnedFakeRuns() = runBlocking {
        onMain { ids.forEach(coordinator::stop) }
        awaitCondition { ids.all { coordinator.snapshots.value[it]?.active != true } }
        onMain { ids.forEach(coordinator::discardFinished) }
        ids.forEach { notificationManager.cancel("generation:$it", 102) }
    }

    @Test
    fun replySurvivesActivityRecreationAndBackgroundAndPostsOnePrivateGenericCompletion() = runBlocking {
        val id = ownedId()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Boolean>()
        val requests = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitCondition { application.generationRuntime.appVisible }
            onMain {
                coordinator.start(id, requestId = "test-request") {
                    flow {
                        requests.incrementAndGet()
                        entered.complete(hasForegroundService())
                        emit(ChatEvent.AssistantMessage(assistant(id)))
                        emit(ChatEvent.Delta("private answer content"))
                        release.await()
                        emit(ChatEvent.Done(Usage(3, 4), false))
                    }
                }
            }
            assertTrue(withTimeout(20_000) { entered.await() })
            val runId = coordinator.snapshots.value.getValue(id).runId
            scenario.recreate()
            awaitCondition { application.generationRuntime.appVisible }
            assertEquals(runId, coordinator.snapshots.value.getValue(id).runId)
            assertEquals(1, requests.get())
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitCondition { !application.generationRuntime.appVisible }
            assertTrue(coordinator.snapshots.value.getValue(id).active)
            release.complete(Unit)
            awaitCondition { completion(id) != null && coordinator.snapshots.value[id]?.active == false }
            val delivered = requireNotNull(completion(id))
            val notification = delivered.notification
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            val displayed = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() +
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            assertFalse(displayed.contains("private answer content"))
            assertFalse(displayed.contains(id))
            assertNotNull(notification.publicVersion)
            assertNotNull(notification.contentIntent)
            assertEquals(GenerationStatus.SUCCEEDED, coordinator.snapshots.value.getValue(id).status)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitCondition { application.generationRuntime.appVisible }
            scenario.recreate()
            awaitCondition { application.generationRuntime.appVisible }
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitCondition { !application.generationRuntime.appVisible }
            assertEquals(delivered.postTime, requireNotNull(completion(id)).postTime)
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun ongoingNotificationStopCancelsTheSelectedConversationAndKeepsTheOtherReplyRunning() = runBlocking {
        val first = ownedId()
        val second = ownedId()
        val cancellations = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitCondition { application.generationRuntime.appVisible }
            onMain {
                listOf(first, second).forEach { id ->
                    coordinator.start(id) {
                        flow {
                            emit(ChatEvent.AssistantMessage(assistant(id)))
                            try { awaitCancellation() } finally { if (id == first) cancellations.incrementAndGet() }
                        }
                    }
                }
            }
            awaitCondition {
                coordinator.snapshots.value[first]?.status == GenerationStatus.RUNNING &&
                    coordinator.snapshots.value[second]?.status == GenerationStatus.RUNNING &&
                    ongoing()?.notification?.actions?.size == 2
            }
            requireNotNull(ongoing()).notification.actions.first().actionIntent.send()
            awaitCondition { coordinator.snapshots.value[first]?.active == false }
            assertEquals(GenerationStatus.CANCELLED, coordinator.snapshots.value.getValue(first).status)
            assertTrue(coordinator.snapshots.value.getValue(second).active)
            assertEquals(1, cancellations.get())
            assertNull(completion(first))
            onMain { coordinator.stop(second) }
            awaitCondition { coordinator.snapshots.value[second]?.active == false }
        }
    }

    @Test
    fun completingWhileTheAppIsVisibleDoesNotPostACompletionAlert() = runBlocking {
        val id = ownedId()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitCondition { application.generationRuntime.appVisible }
            onMain {
                coordinator.start(id) { flow { emit(ChatEvent.Done(Usage(), false)) } }
            }
            awaitCondition { coordinator.snapshots.value[id]?.status == GenerationStatus.SUCCEEDED && ongoing() == null }
            assertNull(completion(id))
        }
    }

    private fun ownedId() = "service-test-${UUID.randomUUID()}".also(ids::add)
    private fun assistant(id: String) = ChatMessage(conversationId = id, role = "assistant", status = "generating")
    private fun completion(id: String) = notificationManager.activeNotifications.firstOrNull { it.tag == "generation:$id" }
    private fun ongoing() = notificationManager.activeNotifications.firstOrNull { it.notification.channelId == "generation_ongoing" }

    @Suppress("DEPRECATION")
    private fun hasForegroundService(): Boolean = (application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .getRunningServices(100).any { it.service.className == GenerationService::class.java.name && it.foreground }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(20_000) { while (!condition()) delay(25) }
    }
}
