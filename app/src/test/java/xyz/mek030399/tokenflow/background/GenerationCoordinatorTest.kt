package xyz.mek030399.tokenflow.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import xyz.mek030399.tokenflow.data.ChatEvent
import xyz.mek030399.tokenflow.data.ChatMessage
import xyz.mek030399.tokenflow.data.ComposerDraft
import xyz.mek030399.tokenflow.data.ProcessEvent
import xyz.mek030399.tokenflow.data.Usage

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationCoordinatorTest {
    private fun user() = ChatMessage(id = "question", conversationId = "chat", role = "user", requestId = "request", content = "Question")
    private fun assistant() = ChatMessage(id = "answer", conversationId = "chat", role = "assistant", requestId = "request", status = "generating")

    @Test fun recreatedObserversRecoverTheCompleteStreamWithoutRestartingTheModel() = runTest {
        val applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = GenerationCoordinator(applicationScope)
        val continueReply = CompletableDeferred<Unit>()
        var requests = 0
        coordinator.start("chat", "request") {
            flow {
                requests++
                emit(ChatEvent.UserMessage(user()))
                emit(ChatEvent.AssistantMessage(assistant()))
                emit(ChatEvent.Delta("First "))
                emit(ChatEvent.Process(ProcessEvent(type = "thinking", id = "thinking", content = "reason")))
                continueReply.await()
                emit(ChatEvent.Delta("second"))
                emit(ChatEvent.Process(ProcessEvent(type = "thinking", id = "thinking", content = "ing")))
                emit(ChatEvent.Done(Usage(10, 4), false))
            }
        }
        val oldUi = launch { coordinator.snapshots.collect {} }
        runCurrent()
        oldUi.cancel()
        assertTrue(coordinator.snapshots.value.getValue("chat").active)
        assertEquals("First ", coordinator.snapshots.first().getValue("chat").assistantMessage?.content)
        assertEquals("Question", coordinator.snapshots.first().getValue("chat").userMessage?.message?.content)
        continueReply.complete(Unit)
        advanceUntilIdle()
        val restored = coordinator.snapshots.first().getValue("chat")
        assertEquals(1, requests)
        assertEquals("First second", restored.assistantMessage?.content)
        assertEquals("reasoning", restored.events.single().content)
        assertEquals(Usage(10, 4), restored.usage)
        assertEquals(GenerationStatus.SUCCEEDED, restored.status)
        applicationScope.cancel()
    }

    @Test fun duplicateConversationDoesNotStartAnotherRequestAndOtherConversationsRemainIndependent() = runTest {
        val coordinator = GenerationCoordinator(backgroundScope)
        var calls = 0
        fun pending() = flow<ChatEvent> { calls++; awaitCancellation() }
        assertNotNull(coordinator.start("one", stream = ::pending))
        assertNull(coordinator.start("one", stream = ::pending))
        assertNotNull(coordinator.start("two", stream = ::pending))
        runCurrent()
        assertEquals(2, calls)
        assertTrue(coordinator.stop("one"))
        runCurrent()
        assertFalse(coordinator.snapshots.value.getValue("one").active)
        assertTrue(coordinator.snapshots.value.getValue("two").active)
        coordinator.stopAll()
        runCurrent()
    }

    @Test fun rejectedForegroundStartNeverCallsTheModelOrSettlement() = runTest {
        val error = IllegalStateException("Service startup not allowed")
        val coordinator = GenerationCoordinator(backgroundScope, object : GenerationLifecycle {
            override fun onStarting(snapshot: GenerationSnapshot) { throw error }
        })
        var calls = 0
        var settlements = 0
        try {
            coordinator.start("chat", onSettled = { settlements++ }) {
                flow { calls++; emit(ChatEvent.Done(Usage(), false)) }
            }
            fail("Expected startup rejection")
        } catch (actual: IllegalStateException) { assertSame(error, actual) }
        runCurrent()
        assertEquals(0, calls)
        assertEquals(0, settlements)
        assertEquals(GenerationStatus.FAILED, coordinator.snapshots.value.getValue("chat").status)
    }

    @Test fun modelWaitsForRealForegroundPromotionAndAsyncServiceFailurePreservesTheReason() = runTest {
        val ready = CompletableDeferred<Unit>()
        val coordinator = GenerationCoordinator(backgroundScope, object : GenerationLifecycle {
            override suspend fun awaitReady(snapshot: GenerationSnapshot) { ready.await() }
        })
        var calls = 0
        var prepared = false
        var settled: GenerationSnapshot? = null
        coordinator.start("chat", beforeStart = { prepared = true }, onSettled = { settled = it }) {
            flow { calls++; emit(ChatEvent.Done(Usage(), false)) }
        }
        runCurrent()
        assertFalse(prepared)
        assertEquals(0, calls)
        val error = IllegalStateException("Foreground promotion failed")
        coordinator.failAll(error)
        runCurrent()
        assertEquals(GenerationStatus.FAILED, settled?.status)
        assertSame(error, settled?.error)
        assertEquals(0, calls)
        assertFalse(prepared)
    }

    @Test fun resolvingANewConversationWaitsForForegroundReadinessAndKeepsOneRunWhileRekeying() = runTest {
        val ready = CompletableDeferred<Unit>()
        val create = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        var creations = 0
        var requests = 0
        var moves = 0
        val coordinator = GenerationCoordinator(backgroundScope, object : GenerationLifecycle {
            override suspend fun awaitReady(snapshot: GenerationSnapshot) { ready.await() }
        })
        coordinator.startResolvingConversation("pending", "request", ComposerDraft("question", submittedRequestId = "request"),
            resolveConversationId = { creations++; create.await(); "actual" },
            onResolved = { old, new -> assertEquals("pending", old); assertEquals("actual", new); moves++ },
            stream = { id -> flow { assertEquals("actual", id); requests++; reply.await(); emit(ChatEvent.Done(Usage(), false)) } },
        )
        val run = coordinator.snapshots.value.getValue("pending").runId
        runCurrent()
        assertEquals(0, creations)
        ready.complete(Unit)
        runCurrent()
        assertEquals(1, creations)
        assertEquals(0, requests)
        assertTrue(coordinator.snapshots.value.getValue("pending").active)
        create.complete(Unit)
        runCurrent()
        assertFalse(coordinator.snapshots.value.containsKey("pending"))
        assertEquals(run, coordinator.snapshots.value.getValue("actual").runId)
        assertEquals("request", coordinator.snapshots.value.getValue("actual").requestId)
        assertEquals("pending", coordinator.snapshots.value.getValue("actual").originConversationId)
        assertEquals(1, moves)
        assertEquals(1, requests)
        assertNull(coordinator.start("actual") { flow { emit(ChatEvent.Done(Usage(), false)) } })
        reply.complete(Unit)
        runCurrent()
        assertEquals(GenerationStatus.SUCCEEDED, coordinator.snapshots.value.getValue("actual").status)
    }

    @Test fun cancellationBeforeCoroutineBodySettlesExactlyOnceWithoutAcceptingADraft() = runTest {
        val coordinator = GenerationCoordinator(backgroundScope)
        var prepared = false
        var calls = 0
        var settlements = 0
        var notifications = 0
        val recorded = mutableListOf<GenerationSnapshot>()
        val other = GenerationCoordinator(backgroundScope, object : GenerationLifecycle {
            override fun onFinished(snapshot: GenerationSnapshot) { notifications++ }
        })
        other.start("chat", beforeStart = { prepared = true }, onSettled = { settlements++; recorded += it }) {
            flow { calls++; awaitCancellation() }
        }
        assertTrue(other.stop("chat"))
        assertFalse(other.stop("chat"))
        runCurrent()
        assertFalse(prepared)
        assertEquals(0, calls)
        assertEquals(1, settlements)
        assertEquals(1, notifications)
        assertFalse(recorded.single().userMessageAccepted)
        assertEquals(GenerationStatus.CANCELLED, recorded.single().status)
        assertTrue(coordinator.snapshots.value.isEmpty())
    }

    @Test fun acceptedCancellationPreservesPartialReplyAndFinishesDraftCleanupBeforePublishingInactive() = runTest {
        val coordinator = GenerationCoordinator(backgroundScope)
        val cleanup = CompletableDeferred<Unit>()
        var settlements = 0
        coordinator.start("chat", "request", onSettled = {
            settlements++
            assertTrue(it.userMessageAccepted)
            assertEquals("Partial", it.assistantMessage?.content)
            cleanup.await()
        }) {
            flow {
                emit(ChatEvent.UserMessage(user()))
                emit(ChatEvent.AssistantMessage(assistant()))
                emit(ChatEvent.Delta("Partial"))
                awaitCancellation()
            }
        }
        runCurrent()
        coordinator.stop("chat")
        runCurrent()
        assertTrue(coordinator.snapshots.value.getValue("chat").active)
        assertTrue(coordinator.snapshots.value.getValue("chat").stopping)
        assertNull(coordinator.start("chat") { flow { emit(ChatEvent.Done(Usage(), false)) } })
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(1, settlements)
        assertEquals(GenerationStatus.CANCELLED, coordinator.snapshots.value.getValue("chat").status)
    }

    @Test fun preparationFailureHasNoUserAcceptanceAndAllowsAnExplicitFreshRetry() = runTest {
        val coordinator = GenerationCoordinator(backgroundScope)
        var calls = 0
        var settled: GenerationSnapshot? = null
        coordinator.start("chat", beforeStart = { throw IllegalStateException("Draft persistence failed") }, onSettled = { settled = it }) {
            flow { calls++; emit(ChatEvent.Done(Usage(), false)) }
        }
        runCurrent()
        assertEquals(0, calls)
        assertEquals(GenerationStatus.FAILED, settled?.status)
        assertFalse(requireNotNull(settled).userMessageAccepted)
        val firstRun = requireNotNull(settled).runId
        coordinator.start("chat") { flow { calls++; emit(ChatEvent.Done(Usage(), false)) } }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(coordinator.snapshots.value.getValue("chat").runId > firstRun)
        assertEquals(GenerationStatus.SUCCEEDED, coordinator.snapshots.value.getValue("chat").status)
    }

    @Test fun incompleteFlowIsAFailureRatherThanASpuriousCompletion() = runTest {
        val coordinator = GenerationCoordinator(backgroundScope)
        coordinator.start("chat") { flow { emit(ChatEvent.AssistantMessage(assistant())); emit(ChatEvent.Delta("unfinished")) } }
        runCurrent()
        assertEquals(GenerationStatus.FAILED, coordinator.snapshots.value.getValue("chat").status)
        assertEquals("unfinished", coordinator.snapshots.value.getValue("chat").assistantMessage?.content)
    }

    @Test fun finishingListenerAndSettlementAreInvokedOnceAndListenerFailureDoesNotRewriteSuccess() = runTest {
        var notifications = 0
        var settlements = 0
        val coordinator = GenerationCoordinator(backgroundScope, object : GenerationLifecycle {
            override fun onFinished(snapshot: GenerationSnapshot) { notifications++; throw SecurityException("Notification denied") }
        })
        coordinator.start("chat", onSettled = { settlements++ }) { flow { emit(ChatEvent.Done(Usage(), false)) } }
        runCurrent()
        assertEquals(1, notifications)
        assertEquals(1, settlements)
        assertEquals(GenerationStatus.SUCCEEDED, coordinator.snapshots.value.getValue("chat").status)
        coordinator.discardFinished("chat")
        assertTrue(coordinator.snapshots.value.isEmpty())
    }
}
