package xyz.mek030399.tokenflow.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryLockingTest {
    @Test
    fun orderedMutexesWaitForTheLowestKeyFirst() = runTest {
        val first = Mutex(locked = true)
        val second = Mutex(locked = true)
        val entered = CompletableDeferred<Unit>()
        val operation = launch {
            withOrderedMutexes(listOf("b" to second, "a" to first)) {
                entered.complete(Unit)
            }
        }

        runCurrent()
        second.unlock()
        runCurrent()

        assertFalse(second.isLocked)
        assertFalse(entered.isCompleted)

        first.unlock()
        operation.join()

        assertTrue(entered.isCompleted)
        assertFalse(first.isLocked)
        assertFalse(second.isLocked)
    }

    @Test
    fun cancellationReleasesLocksAcquiredBeforeTheWait() = runTest {
        val first = Mutex()
        val second = Mutex(locked = true)
        val operation = launch {
            withOrderedMutexes(listOf("b" to second, "a" to first)) {
                error("The second lock should still be held")
            }
        }

        runCurrent()
        assertTrue(first.isLocked)

        operation.cancelAndJoin()

        assertFalse(first.isLocked)
        second.unlock()
    }

    @Test
    fun concurrentIterationHonorsTheConfiguredLimit() = runTest {
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val fourStarted = CompletableDeferred<Unit>()
        val operation = launch {
            (1..12).forEachConcurrent(maxConcurrency = 4) {
                val current = active.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, current) }
                if (current == 4) fourStarted.complete(Unit)
                try {
                    release.await()
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        runCurrent()

        assertTrue(fourStarted.isCompleted)
        assertEquals(4, active.get())
        assertEquals(4, peak.get())

        release.complete(Unit)
        operation.join()

        assertEquals(0, active.get())
        assertEquals(4, peak.get())
    }

    @Test
    fun regenerationUsesTheUserImmediatelyBeforeTheReplacedAssistant() {
        val oldUser = ChatMessage(id = "old-user", conversationId = "conversation", role = "user")
        val currentUser = ChatMessage(id = "current-user", conversationId = "conversation", role = "user")
        val assistant = ChatMessage(id = "assistant", conversationId = "conversation", role = "assistant")
        val messages = listOf(
            oldUser,
            ChatMessage(id = "old-assistant", conversationId = "conversation", role = "assistant"),
            ChatMessage(id = "boundary", conversationId = "conversation", role = CONTEXT_BOUNDARY_ROLE),
            currentUser,
            assistant,
        )

        assertEquals(currentUser, regenerationSourceUser(messages, assistant.id))
    }

    @Test
    fun failureBeforeCommitRollsBackWithoutMaskingTheOriginalError() = runTest {
        val original = IllegalStateException("upload failed")
        var rolledBack = false
        var caught: Throwable? = null

        try {
            runWithRollbackBeforeCommit(
                rollback = {
                    rolledBack = true
                    throw IllegalArgumentException("rollback also failed")
                },
                action = { throw original },
            )
        } catch (failure: Throwable) {
            caught = failure
        }

        assertTrue(rolledBack)
        assertSame(original, caught)
    }

    @Test
    fun cancellationBeforeCommitRunsRollbackInNonCancellableContext() = runTest {
        val started = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        var rolledBack = false
        val operation = launch {
            runWithRollbackBeforeCommit(
                rollback = {
                    yield()
                    rolledBack = true
                },
                action = {
                    started.complete(Unit)
                    neverComplete.await()
                },
            )
        }

        runCurrent()
        assertTrue(started.isCompleted)

        operation.cancelAndJoin()

        assertTrue(rolledBack)
    }
}
