package xyz.mek030399.tokenflow.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mek030399.tokenflow.data.ChatEvent
import xyz.mek030399.tokenflow.data.ChatMessage
import xyz.mek030399.tokenflow.data.ComposerDraft
import xyz.mek030399.tokenflow.data.EditedQuestionSubmission
import xyz.mek030399.tokenflow.data.ProcessEvent
import xyz.mek030399.tokenflow.data.Usage
import java.util.concurrent.atomic.AtomicBoolean

enum class GenerationStatus { STARTING, RUNNING, STOPPING, SUCCEEDED, FAILED, CANCELLED }

/** The latest complete in-memory view of a run; observing UI lifetimes do not own the request. */
data class GenerationSnapshot(
    val conversationId: String,
    val runId: Long,
    val requestId: String?,
    val status: GenerationStatus,
    val userMessage: ChatEvent.UserMessage? = null,
    val assistantMessage: ChatMessage? = null,
    val hasDelta: Boolean = false,
    val events: List<ProcessEvent> = emptyList(),
    val usage: Usage = Usage(),
    val error: Throwable? = null,
    val revision: Long = 0,
    val submittedDraft: ComposerDraft? = null,
    val originConversationId: String = conversationId,
    val editedQuestion: EditedQuestionSubmission? = null,
) {
    val active: Boolean get() = status == GenerationStatus.STARTING ||
        status == GenerationStatus.RUNNING || status == GenerationStatus.STOPPING
    val stopping: Boolean get() = status == GenerationStatus.STOPPING
    val userMessageAccepted: Boolean get() = userMessage != null
}

data class GenerationUpdate(
    val conversationId: String,
    val runId: Long,
    val revision: Long,
    val event: ChatEvent,
)

interface GenerationLifecycle {
    /** Synchronous: rejecting foreground-service startup prevents any model request. */
    fun onStarting(snapshot: GenerationSnapshot) {}
    suspend fun awaitReady(snapshot: GenerationSnapshot) {}
    fun onFinished(snapshot: GenerationSnapshot) {}
}

/**
 * Lives in the application scope. A service and any number of recreated ViewModels only observe it.
 * Nothing is persisted or replayed as a new request after process death.
 */
class GenerationCoordinator(
    private val scope: CoroutineScope,
    private val lifecycle: GenerationLifecycle = object : GenerationLifecycle {},
) {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private var nextRunId = 0L
    private val mutableSnapshots = MutableStateFlow<Map<String, GenerationSnapshot>>(emptyMap())
    private val mutableUpdates = MutableSharedFlow<GenerationUpdate>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snapshots: StateFlow<Map<String, GenerationSnapshot>> = mutableSnapshots.asStateFlow()
    val updates: SharedFlow<GenerationUpdate> = mutableUpdates.asSharedFlow()

    fun start(
        conversationId: String,
        requestId: String? = null,
        beforeStart: suspend () -> Unit = {},
        onSettled: suspend (GenerationSnapshot) -> Unit = {},
        submittedDraft: ComposerDraft? = null,
        stream: () -> Flow<ChatEvent>,
    ): Job? = startInternal(conversationId, requestId, beforeStart, onSettled, submittedDraft,
        stream = { stream() })

    fun startResolvingConversation(
        provisionalConversationId: String,
        requestId: String,
        submittedDraft: ComposerDraft? = null,
        beforeStart: suspend () -> Unit = {},
        resolveConversationId: suspend () -> String,
        onResolved: suspend (String, String) -> Unit = { _, _ -> },
        onSettled: suspend (GenerationSnapshot) -> Unit = {},
        editedQuestion: EditedQuestionSubmission? = null,
        stream: (String) -> Flow<ChatEvent>,
    ): Job? = startInternal(provisionalConversationId, requestId, beforeStart, onSettled, submittedDraft,
        resolveConversationId, onResolved, editedQuestion, stream)

    private fun startInternal(
        conversationId: String,
        requestId: String?,
        beforeStart: suspend () -> Unit,
        onSettled: suspend (GenerationSnapshot) -> Unit,
        submittedDraft: ComposerDraft?,
        resolveConversationId: (suspend () -> String)? = null,
        onResolved: suspend (String, String) -> Unit = { _, _ -> },
        editedQuestion: EditedQuestionSubmission? = null,
        stream: (String) -> Flow<ChatEvent>,
    ): Job? = synchronized(lock) {
        if (jobs[conversationId]?.isActive == true || mutableSnapshots.value[conversationId]?.active == true) {
            return@synchronized null
        }
        val initial = GenerationSnapshot(conversationId, ++nextRunId, requestId, GenerationStatus.STARTING,
            submittedDraft = submittedDraft, editedQuestion = editedQuestion)
        put(initial)
        try {
            lifecycle.onStarting(initial)
        } catch (error: Throwable) {
            put(initial.copy(status = GenerationStatus.FAILED, error = error))
            throw error
        }
        val settled = AtomicBoolean(false)
        var completed = false
        var terminalError: Throwable? = null
        var cancelled = false
        var currentId = conversationId

        suspend fun settle() {
            if (!settled.compareAndSet(false, true)) return
            withContext(NonCancellable) {
                var final = synchronized(lock) {
                    val latest = mutableSnapshots.value[currentId]?.takeIf { it.runId == initial.runId } ?: initial
                    latest.copy(
                        status = when {
                            cancelled -> GenerationStatus.CANCELLED
                            terminalError != null -> GenerationStatus.FAILED
                            completed -> GenerationStatus.SUCCEEDED
                            else -> GenerationStatus.CANCELLED
                        },
                        error = terminalError,
                        revision = latest.revision + 1,
                    )
                }
                try {
                    onSettled(final)
                } catch (error: Throwable) {
                    if (final.error == null && final.status != GenerationStatus.CANCELLED) {
                        final = final.copy(status = GenerationStatus.FAILED, error = error)
                    }
                } finally {
                    synchronized(lock) {
                        if (mutableSnapshots.value[currentId]?.runId == initial.runId) {
                            jobs.remove(currentId)
                            put(final)
                        }
                    }
                    // Notification availability must not change the request or draft outcome.
                    runCatching { lifecycle.onFinished(final) }
                }
            }
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                lifecycle.awaitReady(initial)
                currentCoroutineContext().ensureActive()
                beforeStart()
                currentCoroutineContext().ensureActive()
                resolveConversationId?.invoke()?.takeIf { it != currentId }?.let { resolvedId ->
                    withContext(NonCancellable) {
                        synchronized(lock) {
                            check(mutableSnapshots.value[resolvedId]?.active != true) { "Conversation is already generating" }
                        }
                        // Move the pending draft and snapshot as one non-cancellable preparation step.
                        onResolved(currentId, resolvedId)
                        synchronized(lock) {
                            val oldId = currentId
                            val latest = mutableSnapshots.value.getValue(oldId)
                            jobs.remove(oldId)?.let { jobs[resolvedId] = it }
                            currentId = resolvedId
                            mutableSnapshots.value = (mutableSnapshots.value - oldId) +
                                (resolvedId to latest.copy(conversationId = resolvedId, revision = latest.revision + 1))
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    val latest = mutableSnapshots.value.getValue(currentId)
                    if (!latest.stopping) put(latest.copy(status = GenerationStatus.RUNNING))
                }
                stream(currentId).collect { event ->
                    synchronized(lock) {
                        val latest = mutableSnapshots.value.getValue(currentId)
                        val updated = latest.apply(event)
                        put(updated)
                        mutableUpdates.tryEmit(GenerationUpdate(currentId, initial.runId, updated.revision, event))
                        if (event is ChatEvent.Done) completed = true
                    }
                }
                if (!completed) terminalError = IllegalStateException("Generation ended before completion")
            } catch (error: CancellationException) {
                terminalError = synchronized(lock) { mutableSnapshots.value[currentId]?.error }
                cancelled = terminalError == null
                throw error
            } catch (error: Throwable) {
                terminalError = error
            } finally {
                settle()
            }
        }
        jobs[conversationId] = job
        job.invokeOnCompletion { error ->
            if (!settled.get()) {
                // A cancelled lazy coroutine never enters its body or finally block.
                terminalError = mutableSnapshots.value[currentId]?.error ?: error?.takeUnless { it is CancellationException } ?: terminalError
                cancelled = terminalError == null && (error is CancellationException || !completed)
                scope.launch(NonCancellable) { settle() }
            }
        }
        job.start()
        job
    }

    fun stop(conversationId: String): Boolean = synchronized(lock) {
        val job = jobs[conversationId] ?: return@synchronized false
        val snapshot = mutableSnapshots.value[conversationId] ?: return@synchronized false
        if (!snapshot.active || snapshot.stopping) return@synchronized false
        put(snapshot.copy(status = GenerationStatus.STOPPING, revision = snapshot.revision + 1))
        job.cancel()
        true
    }

    fun stopAll() {
        val ids = synchronized(lock) { jobs.keys.toList() }
        ids.forEach(::stop)
    }

    fun stopRun(runId: Long): Boolean = synchronized(lock) {
        mutableSnapshots.value.values.firstOrNull { it.runId == runId && it.active }
            ?.let { stop(it.conversationId) } ?: false
    }

    /** A system foreground-service failure stops live requests without losing the failure reason. */
    fun failAll(error: Throwable) = synchronized(lock) {
        jobs.toList().forEach { (id, job) ->
            mutableSnapshots.value[id]?.takeIf { it.active }?.let { snapshot ->
                put(snapshot.copy(status = GenerationStatus.STOPPING, error = error, revision = snapshot.revision + 1))
                job.cancel()
            }
        }
    }

    fun discardFinished(conversationId: String) = synchronized(lock) {
        if (mutableSnapshots.value[conversationId]?.active == false) {
            mutableSnapshots.value = mutableSnapshots.value - conversationId
        }
    }

    private fun put(snapshot: GenerationSnapshot) {
        mutableSnapshots.value = mutableSnapshots.value + (snapshot.conversationId to snapshot)
    }
}

private fun GenerationSnapshot.apply(event: ChatEvent): GenerationSnapshot {
    val next = when (event) {
        is ChatEvent.UserMessage -> copy(userMessage = event)
        is ChatEvent.AssistantMessage -> copy(assistantMessage = event.message)
        is ChatEvent.Delta -> copy(
            assistantMessage = assistantMessage?.let { it.copy(content = it.content + event.content) },
            hasDelta = true,
        )
        is ChatEvent.Process -> {
            val index = if (event.event.type == "thinking") {
                events.indexOfLast { it.type == "thinking" && it.id == event.event.id }
            } else -1
            copy(events = if (index < 0) events + event.event else events.toMutableList().also {
                it[index] = it[index].copy(content = it[index].content + event.event.content)
            })
        }
        is ChatEvent.Done -> copy(usage = event.usage)
    }
    return next.copy(revision = revision + 1)
}
