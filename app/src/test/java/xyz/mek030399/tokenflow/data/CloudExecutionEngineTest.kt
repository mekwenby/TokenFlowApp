package xyz.mek030399.tokenflow.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudExecutionEngineTest {
    @Test
    fun requiredStepDropsPrematureReplayTextAndRetainsReasoningUnchanged() = runTest {
        listOf(ProviderProtocol.OPENAI_RESPONSES, ProviderProtocol.ANTHROPIC_MESSAGES).forEach { protocol ->
            val reasoning = ProtocolReplayItem(protocol, 0, buildJsonObject {
                put("type", if (protocol == ProviderProtocol.OPENAI_RESPONSES) "reasoning" else "thinking")
                put("signature", "unchanged-signed-payload")
            })
            val premature = ProtocolReplayItem(protocol, 1, buildJsonObject {
                put("type", if (protocol == ProviderProtocol.OPENAI_RESPONSES) "message" else "text")
                put("text", "Done before anything ran")
            })
            val gateway = ScriptedGateway(
                {
                    emit(ModelStreamEvent.TextDelta("Done before anything ran"))
                    emit(ModelStreamEvent.ReplayItem(reasoning))
                    emit(ModelStreamEvent.ReplayItem(premature))
                    emit(toolCall(CLOUD))
                },
                { emit(ModelStreamEvent.TextDelta("Real result")) },
            )
            val events = DirectChatEngine(gateway, SessionTools()).run(request(protocol), options(), 7).toList()
            val selection = gateway.requests.last().messages.first { it.role == "assistant" }
            assertEquals("", selection.content)
            assertEquals(listOf(reasoning), selection.replayItems)
            assertEquals(listOf(CLOUD), selection.toolCalls.map(CanonicalToolCall::name))
            assertEquals("Real result", (events.last() as EngineEvent.Done).content)
        }
    }

    @Test
    fun requiredStepUsesOnlyBoundCloudToolsThenRestoresAutomaticTools() = runTest {
        val gateway = ScriptedGateway(
            { emit(ModelStreamEvent.TextDelta("Working. ")); emit(toolCall(CLOUD)) },
            { emit(ModelStreamEvent.TextDelta("Finished.")) },
        )
        val tools = SessionTools()
        val events = DirectChatEngine(gateway, tools).run(request(), options(), 7).toList()

        assertEquals(listOf(CLOUD, MCP), gateway.requests[0].tools.map(ToolDefinition::name))
        assertEquals(ToolChoice.REQUIRED, gateway.requests[0].toolChoice)
        assertEquals(tools.allDefinitions, gateway.requests[1].tools)
        assertEquals(ToolChoice.AUTO, gateway.requests[1].toolChoice)
        assertEquals(listOf(CLOUD), tools.executed.map(CanonicalToolCall::name))
        assertEquals("Finished.", (events.last() as EngineEvent.Done).content)
        assertEquals("", gateway.requests[1].messages.first { it.role == "assistant" }.content)
        val dispatchIndex = events.indexOfFirst { it is EngineEvent.Process && it.event.cloudDispatched }
        val firstTextIndex = events.indexOfFirst { it is EngineEvent.Delta }
        assertTrue(dispatchIndex >= 0 && firstTextIndex > dispatchIndex)
        assertTrue(events.filterIsInstance<EngineEvent.Process>().first { it.event.type == "tool_started" }.event.cloudTool)
        assertEquals(1, tools.closed)
    }

    @Test
    fun boundMcpCallCanSatisfyRequiredExecution() = runTest {
        val gateway = ScriptedGateway(
            { emit(toolCall(MCP)) },
            { emit(ModelStreamEvent.TextDelta("MCP result.")) },
        )
        val tools = SessionTools()
        DirectChatEngine(gateway, tools).run(request(), options(), 7).toList()

        assertEquals(listOf(MCP), tools.executed.map(CanonicalToolCall::name))
        assertEquals(ToolChoice.AUTO, gateway.requests.last().toolChoice)
    }

    @Test
    fun requiredTextOnlyFailsWithoutPublishingProposedCode() = runTest {
        val gateway = ScriptedGateway({ emit(ModelStreamEvent.TextDelta("Run this code yourself.")) })
        val tools = SessionTools()
        val events = mutableListOf<EngineEvent>()
        val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList(events) }.exceptionOrNull()

        assertTrue(error is ConfigurationException)
        assertEquals("The model did not invoke a Cloud tool", error?.message)
        assertTrue(tools.executed.isEmpty())
        assertFalse(events.any { it is EngineEvent.Delta || it is EngineEvent.Done })
        assertEquals(1, tools.closed)
    }

    @Test
    fun entireRequiredBatchIsRejectedBeforeAnyUnadvertisedCallCanRun() = runTest {
        val gateway = ScriptedGateway({
            emit(toolCall(CLOUD, index = 0))
            emit(toolCall("calculate", index = 1))
        })
        val tools = SessionTools()
        val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList() }.exceptionOrNull()

        assertTrue(error is ConfigurationException)
        assertEquals("The model returned a tool that was not advertised", error?.message)
        assertTrue(tools.executed.isEmpty())
    }

    @Test
    fun aCloudNamePrefixWithoutSessionBindingDoesNotGrantExecution() = runTest {
        val gateway = ScriptedGateway({ emit(toolCall("cloud_unbound")) })
        val tools = SessionTools()
        val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList() }.exceptionOrNull()

        assertTrue(error is ConfigurationException)
        assertFalse(gateway.requests.single().tools.any { it.name == "cloud_unbound" })
        assertTrue(tools.executed.isEmpty())
    }

    @Test
    fun malformedRequiredArgumentsRejectWholeBatchBeforeDispatch() = runTest {
        listOf("{", "[]", "null", "\"text\"").forEach { arguments ->
            val gateway = ScriptedGateway({
                emit(toolCall(CLOUD, index = 0))
                emit(toolCall(MCP, arguments = arguments, index = 1))
            })
            val tools = SessionTools()
            val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList() }.exceptionOrNull()

            assertTrue(arguments, error is ConfigurationException)
            assertTrue(arguments, tools.executed.isEmpty())
        }
    }

    @Test
    fun invalidSecondBuiltInSchemaRejectsBatchBeforeTheFirstCommandRuns() = runTest {
        val gateway = ScriptedGateway({
            emit(toolCall(CLOUD, index = 0))
            emit(toolCall(CLOUD, arguments = "{\"code\":123}", index = 1))
        })
        val tools = SessionTools()
        val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList() }.exceptionOrNull()
        assertTrue(error is ConfigurationException)
        assertTrue(tools.executed.isEmpty())
    }

    @Test
    fun executorValidationFailureDoesNotSatisfyRequiredExecution() = runTest {
        val gateway = ScriptedGateway({
            emit(ModelStreamEvent.TextDelta("Completed."))
            emit(toolCall(CLOUD))
        })
        val tools = SessionTools { ToolExecutionResult("invalid code argument", false, cloudDispatched = false) }
        val events = mutableListOf<EngineEvent>()
        val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList(events) }.exceptionOrNull()

        assertTrue(error is ConfigurationException)
        assertFalse(events.any { it is EngineEvent.Delta || it is EngineEvent.Done })
        assertFalse(events.filterIsInstance<EngineEvent.Process>().any { it.event.cloudDispatched })
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun realRemoteFailureAllowsAnAutomaticExplanationWithoutForcedRetry() = runTest {
        val gateway = ScriptedGateway(
            { emit(ModelStreamEvent.TextDelta("Completed successfully.")); emit(toolCall(CLOUD)) },
            { emit(ModelStreamEvent.TextDelta("The server rejected the command.")) },
        )
        val tools = SessionTools { ToolExecutionResult("permission denied", false, cloudDispatched = true) }
        val events = DirectChatEngine(gateway, tools).run(request(), options(), 7).toList()

        assertEquals(1, tools.executed.size)
        assertEquals(ToolChoice.AUTO, gateway.requests.last().toolChoice)
        assertTrue(events.filterIsInstance<EngineEvent.Process>().any {
            it.event.type == "tool_failed" && it.event.cloudDispatched
        })
        assertEquals("The server rejected the command.", (events.last() as EngineEvent.Done).content)
    }

    @Test
    fun requiredModeFailsBeforeModelRequestWhenCloudIsUnavailable() = runTest {
        listOf(
            options().copy(enableInfiniteCloud = false) to 7,
            options().copy(cloudServerId = null) to 7,
            options() to 0,
        ).forEach { (options, budget) ->
            val gateway = ScriptedGateway()
            val error = runCatching { DirectChatEngine(gateway, SessionTools()).run(request(), options, budget).toList() }.exceptionOrNull()
            assertTrue(error is ConfigurationException)
            assertTrue(gateway.requests.isEmpty())
        }
        val gateway = ScriptedGateway()
        val tools = SessionTools(boundCloudNames = setOf("missing-definition"))
        val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList() }.exceptionOrNull()
        assertTrue(error is ConfigurationException)
        assertTrue(gateway.requests.isEmpty())
        assertEquals(1, tools.closed)
    }

    @Test
    fun requiredCompatibilityErrorDoesNotSilentlyFallBackToAutomaticChoice() = runTest {
        listOf(400, 422).forEach { status ->
            val gateway = ScriptedGateway({ throw ApiException(status, message = "required unsupported") })
            val tools = SessionTools()
            val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList() }.exceptionOrNull()

            assertTrue(error is ConfigurationException)
            assertEquals("The model provider does not support required Cloud tool calls", error?.message)
            assertEquals(1, gateway.requests.size)
            assertEquals(ToolChoice.REQUIRED, gateway.requests.single().toolChoice)
            assertTrue(tools.executed.isEmpty())
        }
    }

    @Test
    fun otherRequiredRequestValidationErrorsKeepTheirActualCauseWithoutFallback() = runTest {
        val cause = ApiException(400, message = "max_tokens exceeds the model output limit")
        val gateway = ScriptedGateway({ throw cause })
        val tools = SessionTools()
        val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList() }.exceptionOrNull()
        assertTrue(error === cause)
        assertEquals(1, gateway.requests.size)
        assertEquals(ToolChoice.REQUIRED, gateway.requests.single().toolChoice)
        assertTrue(tools.executed.isEmpty())
    }

    @Test
    fun modelTransportRetryPreservesRequiredChoiceWithoutReplayingRemoteCalls() = runTest {
        val gateway = ScriptedGateway(
            { throw IOException("temporary model connection failure") },
            { emit(toolCall(CLOUD)) },
            { throw ApiException(503, message = "temporary model failure") },
            { emit(ModelStreamEvent.TextDelta("Done.")) },
        )
        val tools = SessionTools()
        DirectChatEngine(gateway, tools).run(request(), options(), 7).toList()

        assertEquals(listOf(ToolChoice.REQUIRED, ToolChoice.REQUIRED, ToolChoice.AUTO, ToolChoice.AUTO),
            gateway.requests.map(ModelCallRequest::toolChoice))
        assertEquals(1, tools.executed.size)
        assertEquals(1, tools.closed)
    }

    @Test
    fun anthropicRequiredGenerationKeepsThinkingOffAcrossTheWholeToolCycle() = runTest {
        val gateway = ScriptedGateway(
            { emit(toolCall(CLOUD)) },
            { emit(ModelStreamEvent.TextDelta("Done.")) },
        )
        val initial = request(ProviderProtocol.ANTHROPIC_MESSAGES)
        DirectChatEngine(gateway, SessionTools()).run(initial, options(), 7).toList()

        assertEquals(listOf("off", "off"), gateway.requests.map(ModelCallRequest::thinkingEffort))
        assertEquals("medium", initial.thinkingEffort)
        val autoGateway = ScriptedGateway({ emit(ModelStreamEvent.TextDelta("Explanation.")) })
        DirectChatEngine(autoGateway, SessionTools()).run(initial, options().copy(cloudExecutionMode = CloudExecutionMode.AUTO), 7).toList()
        assertEquals("medium", autoGateway.requests.single().thinkingEffort)
    }

    @Test
    fun exhaustedBudgetRemovesCloudGuidanceAndToolsAfterRequiredExecution() = runTest {
        val gateway = ScriptedGateway(
            { emit(toolCall(CLOUD)) },
            { emit(ModelStreamEvent.TextDelta("Done.")) },
        )
        DirectChatEngine(gateway, SessionTools()).run(request(), options(), 1).toList()

        assertTrue(gateway.requests.last().tools.isEmpty())
        assertEquals(ToolChoice.AUTO, gateway.requests.last().toolChoice)
        assertEquals("BASE", gateway.requests.last().systemPrompt)
    }

    @Test
    fun automaticModeStillAllowsAPlainAnswerAndRejectsUnadvertisedCalls() = runTest {
        val gateway = ScriptedGateway({ emit(ModelStreamEvent.TextDelta("Explanation.")) })
        val tools = SessionTools()
        val events = DirectChatEngine(gateway, tools).run(request(), options().copy(cloudExecutionMode = CloudExecutionMode.AUTO), 7).toList()
        assertEquals(ToolChoice.AUTO, gateway.requests.single().toolChoice)
        assertEquals(tools.allDefinitions, gateway.requests.single().tools)
        assertEquals("Explanation.", (events.last() as EngineEvent.Done).content)

        val invalidGateway = ScriptedGateway({ emit(toolCall("not-advertised")) })
        val error = runCatching {
            DirectChatEngine(invalidGateway, tools).run(request(), options().copy(cloudExecutionMode = CloudExecutionMode.AUTO), 7).toList()
        }.exceptionOrNull()
        assertTrue(error is ConfigurationException)
        assertTrue(tools.executed.isEmpty())
    }

    @Test
    fun cancelledRemoteCallIsNotRetriedAndTheSessionIsClosed() = runTest {
        val gateway = ScriptedGateway({ emit(toolCall(CLOUD)) })
        val tools = SessionTools { throw CancellationException("stopped") }
        val error = runCatching { DirectChatEngine(gateway, tools).run(request(), options(), 7).toList() }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(1, tools.executed.size)
        assertEquals(1, gateway.requests.size)
        assertEquals(1, tools.closed)
    }

    private class ScriptedGateway(vararg steps: suspend FlowCollector<ModelStreamEvent>.() -> Unit) : ModelGateway() {
        val requests = mutableListOf<ModelCallRequest>()
        private val responses = steps.toList()
        override fun stream(request: ModelCallRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            check(requests.size <= responses.size) { "Unexpected model request" }
            responses[requests.lastIndex](this)
            emit(ModelStreamEvent.Completed)
        }
    }

    private class SessionTools(
        private val boundCloudNames: Set<String> = setOf(CLOUD, MCP),
        private val result: (CanonicalToolCall) -> ToolExecutionResult = { ToolExecutionResult("result", true, cloudDispatched = true) },
    ) : ToolRunner {
        val allDefinitions = listOf("calculate", CLOUD, MCP, "cloud_unbound").map { ToolDefinition(it, "fixture", JsonObject(emptyMap())) }
        val executed = mutableListOf<CanonicalToolCall>()
        var closed = 0
        override fun definitions(enableSearch: Boolean, enableRead: Boolean) = allDefinitions
        override suspend fun execute(call: CanonicalToolCall, enableSearch: Boolean, enableRead: Boolean) = error("Use the generation session")
        override suspend fun openSession(options: ToolOptions): ToolSession = object : ToolSession {
            override val definitions = allDefinitions
            override val cloudToolNames = boundCloudNames
            override suspend fun execute(call: CanonicalToolCall): ToolExecutionResult {
                executed += call
                return result(call)
            }
            override suspend fun close() { closed += 1 }
        }
    }

    private fun options() = ToolOptions(false, false, enableInfiniteCloud = true, cloudServerId = "server",
        cloudExecutionMode = CloudExecutionMode.REQUIRED)

    private fun request(protocol: ProviderProtocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS) = ModelCallRequest(
        model = ModelProfile(providerId = "provider", remoteId = "model"),
        provider = ProviderConfig(id = "provider", name = "fixture", baseUrl = "https://example.test", protocol = protocol),
        apiKey = "fixture",
        systemPrompt = "BASE",
        thinkingEffort = "medium",
        messages = listOf(CanonicalMessage("user", "Run the requested code.")),
        tools = emptyList(),
        requestId = "request",
    )

    companion object {
        private const val CLOUD = "cloud_run_python"
        private const val MCP = "mcp__fixture__run"
        private fun toolCall(name: String, arguments: String = if (name == CLOUD) "{\"code\":\"print(1)\"}" else "{}", index: Int = 0) =
            ModelStreamEvent.ToolCallDelta(index = index, id = "call-$index", name = name, arguments = arguments)
    }
}
