package xyz.mek030399.tokenflow.data

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DirectChatEngine(
    private val gateway: ModelGateway,
    private val tools: ToolRunner,
) {
    suspend fun openSession(options: ToolOptions, maxToolCalls: Int): ToolSession? =
        if (maxToolCalls > 0) tools.openSession(options) else null

    fun run(
        initial: ModelCallRequest,
        enableSearch: Boolean,
        enableRead: Boolean,
        maxToolCalls: Int,
        urlReaderBackend: UrlReaderBackend = UrlReaderBackend.BUILT_IN,
    ): Flow<EngineEvent> = run(
        initial,
        ToolOptions(enableSearch, enableRead, urlReaderBackend = urlReaderBackend),
        maxToolCalls,
    )

    fun run(
        initial: ModelCallRequest,
        options: ToolOptions,
        maxToolCalls: Int,
        preparedSession: ToolSession? = null,
    ): Flow<EngineEvent> = flow {
        val transcript = initial.messages.toMutableList()
        val output = StringBuilder()
        val processEvents = mutableListOf<ProcessEvent>()
        var totalUsage = Usage()
        var usedToolCalls = 0
        val cloudRequired = options.cloudExecutionMode == CloudExecutionMode.REQUIRED
        var thinkingEffort = if (cloudRequired && initial.provider.protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
            "off"
        } else initial.thinkingEffort
        var cloudDispatched = false
        var round = 0
        val toolBudget = maxToolCalls.coerceIn(0, 20)
        val toolSession = preparedSession ?: openSession(options, toolBudget)

        try {
            val cloudToolNames = toolSession?.cloudToolNames.orEmpty()
                .intersect(toolSession?.definitions.orEmpty().map(ToolDefinition::name).toSet())
            if (cloudRequired && (!options.enableInfiniteCloud || options.cloudServerId == null ||
                    toolBudget == 0 || cloudToolNames.isEmpty())
            ) {
                throw ConfigurationException("Cloud execution requires an enabled server and a positive tool budget")
            }
            toolSession?.initializationWarnings.orEmpty().forEach { warning ->
                processEvents += warning
                emit(EngineEvent.Process(warning))
            }
            while (true) {
            round += 1
            val requiresCloudCall = cloudRequired && !cloudDispatched
            val definitions = if (usedToolCalls < toolBudget) {
                toolSession?.definitions.orEmpty().filter { !requiresCloudCall || it.name in cloudToolNames }
            } else {
                emptyList()
            }
            val advertisedNames = definitions.map(ToolDefinition::name).toSet()
            val responseText = StringBuilder()
            val calls = linkedMapOf<Int, MutableToolCall>()
            val replayItems = linkedMapOf<Int, ProtocolReplayItem>()
            var receivedProviderContent = false
            var transportAttempt = 0
            var thinkingFallbackUsed = false

            while (true) {
                val callRequest = initial.copy(
                    systemPrompt = SystemPrompts.withCloudCapabilities(initial.systemPrompt, definitions, requiresCloudCall),
                    thinkingEffort = thinkingEffort,
                    messages = transcript.toList(),
                    tools = definitions,
                    toolChoice = if (requiresCloudCall) ToolChoice.REQUIRED else ToolChoice.AUTO,
                )
                initial.model.contextWindowTokens?.let { capacity ->
                    require(ContextBuilder.estimate(callRequest.systemPrompt, callRequest.messages, callRequest.tools) +
                        callRequest.maxOutputTokens <= capacity.toLong()) {
                        "Estimated context exceeds the model capacity. Reduce attachments or compress the conversation."
                    }
                }
                try {
                    gateway.stream(callRequest).collect { event ->
                        when (event) {
                            is ModelStreamEvent.TextDelta -> if (event.content.isNotEmpty()) {
                                receivedProviderContent = true
                                responseText.append(event.content)
                                if (!requiresCloudCall) {
                                    output.append(event.content)
                                    emit(EngineEvent.Delta(event.content))
                                }
                            }
                            is ModelStreamEvent.ThinkingDelta -> if (event.content.isNotEmpty()) {
                                receivedProviderContent = true
                                val process = ProcessEvent(
                                    type = "thinking",
                                    id = "thinking-$round",
                                    content = event.content,
                                )
                                mergeProcess(processEvents, process)
                                emit(EngineEvent.Process(process))
                            }
                            is ModelStreamEvent.ToolCallDelta -> {
                                if (event.id.isNotEmpty() || event.name.isNotEmpty() || event.arguments.isNotEmpty()) {
                                    receivedProviderContent = true
                                }
                                val call = calls.getOrPut(event.index) { MutableToolCall() }
                                if (event.id.isNotEmpty()) call.id = event.id
                                if (event.name.isNotEmpty()) call.name = event.name
                                if (event.replaceArguments) call.arguments.clear()
                                call.arguments.append(event.arguments)
                            }
                            is ModelStreamEvent.ReplayItem -> {
                                receivedProviderContent = true
                                replayItems[event.item.index] = event.item
                            }
                            is ModelStreamEvent.TokenUsage -> totalUsage += event.usage
                            ModelStreamEvent.Completed -> Unit
                        }
                    }
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ApiException) {
                    if (requiresCloudCall && error.status in setOf(400, 422)) {
                        val detail = error.message.lowercase()
                        if (listOf("tool_choice", "tool choice", "required", "forced tool", "function calling", "强制", "工具选择")
                                .any(detail::contains)) {
                            throw ConfigurationException("The model provider does not support required Cloud tool calls")
                        }
                        throw error
                    }
                    if (!receivedProviderContent && !thinkingFallbackUsed && thinkingEffort != "off" &&
                        error.status in setOf(400, 422)
                    ) {
                        thinkingFallbackUsed = true
                        thinkingEffort = "off"
                        val event = ProcessEvent(type = "status", message = "Retrying without reasoning parameters")
                        processEvents += event
                        emit(EngineEvent.Process(event))
                        continue
                    }
                    if (!receivedProviderContent && error.retryable && transportAttempt < MAX_TRANSPORT_RETRIES) {
                        transportAttempt += 1
                        val event = retryEvent(transportAttempt)
                        processEvents += event
                        emit(EngineEvent.Process(event))
                        delay(retryDelayMillis(transportAttempt))
                        continue
                    }
                    throw error
                } catch (error: IOException) {
                    if (!receivedProviderContent && transportAttempt < MAX_TRANSPORT_RETRIES) {
                        transportAttempt += 1
                        val event = retryEvent(transportAttempt)
                        processEvents += event
                        emit(EngineEvent.Process(event))
                        delay(retryDelayMillis(transportAttempt))
                        continue
                    }
                    throw error
                }
            }

            val completedCalls = calls.toSortedMap().values.mapNotNull { it.complete() }
            if (requiresCloudCall && (completedCalls.isEmpty() || completedCalls.size != calls.size)) {
                throw ConfigurationException("The model did not invoke a Cloud tool")
            }
            if (completedCalls.any { it.name !in advertisedNames }) {
                throw ConfigurationException("The model returned a tool that was not advertised")
            }
            if (requiresCloudCall) {
                val builtInCloudDefinitions = infiniteCloudToolDefinitions(options).associateBy(ToolDefinition::name)
                val invalidArguments = completedCalls.any { call ->
                    runCatching {
                        val arguments = DirectApiTransport.defaultJson.parseToJsonElement(call.arguments) as JsonObject
                        builtInCloudDefinitions[call.name]?.let { validateCloudToolArguments(it, arguments) }
                        false
                    }.getOrDefault(true)
                }
                if (invalidArguments) {
                    throw ConfigurationException("Cloud tool arguments were invalid; no Cloud execution was started")
                }
            }
            if (responseText.isNotEmpty() || completedCalls.isNotEmpty() || replayItems.isNotEmpty()) {
                transcript += CanonicalMessage(
                    role = "assistant",
                    content = if (requiresCloudCall) "" else responseText.toString(),
                    toolCalls = completedCalls,
                    replayItems = replayItems.toSortedMap().values.filter { item ->
                        if (!requiresCloudCall) true else when (item.protocol) {
                            ProviderProtocol.OPENAI_RESPONSES -> (item.payload["type"] as? JsonPrimitive)?.content == "reasoning"
                            ProviderProtocol.ANTHROPIC_MESSAGES -> (item.payload["type"] as? JsonPrimitive)?.content in
                                setOf("thinking", "redacted_thinking")
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> false
                        }
                    },
                )
            }
            if (completedCalls.isEmpty()) break

            completedCalls.forEach { call ->
                val canExecute = usedToolCalls < toolBudget
                val started = ProcessEvent(
                    type = "tool_started",
                    id = call.id,
                    name = call.name,
                    arguments = call.arguments,
                    ok = canExecute,
                    cloudTool = canExecute && call.name in cloudToolNames,
                )
                processEvents += started
                emit(EngineEvent.Process(started))

                val result = if (canExecute) {
                    usedToolCalls += 1
                    requireNotNull(toolSession).execute(call)
                } else {
                    ToolExecutionResult("{\"error\":\"Maximum tool call limit reached\"}", false)
                }
                result.processEvent?.let { diagnostic ->
                    processEvents += diagnostic
                    emit(EngineEvent.Process(diagnostic))
                }
                val completed = ProcessEvent(
                    type = if (result.ok) "tool_completed" else "tool_failed",
                    id = call.id,
                    name = call.name,
                    arguments = call.arguments,
                    result = result.content,
                    ok = result.ok,
                    knowledgeCitations = result.citations,
                    cloudDispatched = result.cloudDispatched,
                    cloudTool = call.name in cloudToolNames,
                )
                processEvents += completed
                emit(EngineEvent.Process(completed))
                transcript += CanonicalMessage(
                    role = "tool",
                    content = result.content,
                    toolCallId = call.id,
                    toolName = call.name,
                )
                if (requiresCloudCall && !cloudDispatched) {
                    if (!result.cloudDispatched) {
                        throw ConfigurationException("Cloud tool arguments were invalid; no Cloud execution was started")
                    }
                    cloudDispatched = true
                }
            }
            }

            emit(EngineEvent.Done(output.toString(), totalUsage, processEvents.toList()))
        } finally {
            if (preparedSession == null) toolSession?.close()
        }
    }

    suspend fun generateTitle(request: ModelCallRequest): String {
        val output = StringBuilder()
        gateway.stream(
            request.copy(
                thinkingEffort = "off",
                tools = emptyList(),
                toolChoice = ToolChoice.AUTO,
                maxOutputTokens = 80,
                requestId = UUID.randomUUID().toString(),
            ),
        ).collect { event ->
            if (event is ModelStreamEvent.TextDelta && output.length < 160) output.append(event.content)
        }
        return output.toString().trim().trim('"', '\'', '`').lineSequence().firstOrNull().orEmpty().take(120)
    }

    private fun retryEvent(attempt: Int) = ProcessEvent(
        type = "status",
        messageKey = "retrying",
        attempt = attempt,
        maxAttempts = MAX_TRANSPORT_RETRIES,
    )

    private fun retryDelayMillis(attempt: Int): Long = 600L * attempt.coerceIn(1, MAX_TRANSPORT_RETRIES)

    private fun mergeProcess(events: MutableList<ProcessEvent>, incoming: ProcessEvent) {
        val index = events.indexOfLast { it.type == "thinking" && it.id == incoming.id }
        if (index < 0) events += incoming
        else events[index] = events[index].copy(content = events[index].content + incoming.content)
    }

    private data class MutableToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun complete(): CanonicalToolCall? {
            if (name.isBlank()) return null
            return CanonicalToolCall(id.ifBlank { UUID.randomUUID().toString() }, name, arguments.toString().ifBlank { "{}" })
        }
    }

    companion object {
        const val MAX_TRANSPORT_RETRIES = 2
    }
}
