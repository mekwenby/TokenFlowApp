package xyz.mek030399.tokenflow.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeCitationPropagationTest {
    @Test
    fun toolCompletionAndEngineDoneKeepKnowledgeCitations() = runTest {
        val citation = KnowledgeCitation(
            chunkId = 9,
            documentId = "document-9",
            documentName = "Runbook.md",
            position = 3,
        )
        val gateway = ToolCallingGateway()
        val engine = DirectChatEngine(gateway, CitationToolRunner(citation))

        val events = engine.run(
            initial = ModelCallRequest(
                model = ModelProfile(providerId = "provider", remoteId = "model"),
                provider = ProviderConfig(
                    id = "provider",
                    name = "Provider",
                    baseUrl = "https://api.example.com/v1",
                    protocol = ProviderProtocol.OPENAI_RESPONSES,
                ),
                apiKey = "secret",
                systemPrompt = "system",
                thinkingEffort = "off",
                messages = listOf(CanonicalMessage("user", "Find the runbook")),
                tools = emptyList(),
                requestId = "request-1",
            ),
            options = ToolOptions(enableSearch = false, enableRead = false, enableKnowledge = true),
            maxToolCalls = 1,
        ).toList()

        val completed = events.filterIsInstance<EngineEvent.Process>()
            .single { it.event.type == "tool_completed" }.event
        val done = events.last() as EngineEvent.Done

        assertEquals("search_knowledge", completed.name)
        assertEquals(listOf(citation), completed.knowledgeCitations)
        assertEquals(
            listOf(citation),
            done.events.single { it.type == "tool_completed" }.knowledgeCitations,
        )
        assertTrue(done.content.contains("done"))
    }
}

private class ToolCallingGateway : ModelGateway() {
    private var callCount = 0

    override fun stream(request: ModelCallRequest): Flow<ModelStreamEvent> = flow {
        if (callCount++ == 0) {
            emit(
                ModelStreamEvent.ToolCallDelta(
                    index = 0,
                    id = "call-1",
                    name = "search_knowledge",
                    arguments = "{\"query\":\"runbook\"}",
                ),
            )
        } else {
            emit(ModelStreamEvent.TextDelta("done"))
        }
        emit(ModelStreamEvent.Completed)
    }
}

private class CitationToolRunner(
    private val citation: KnowledgeCitation,
) : ToolRunner {
    override fun definitions(enableSearch: Boolean, enableRead: Boolean): List<ToolDefinition> = emptyList()

    override fun definitions(options: ToolOptions): List<ToolDefinition> = listOf(
        ToolDefinition("search_knowledge", "Search knowledge", JsonObject(emptyMap())),
    )

    override suspend fun execute(
        call: CanonicalToolCall,
        enableSearch: Boolean,
        enableRead: Boolean,
    ): ToolExecutionResult = knowledgeResult()

    override suspend fun execute(
        call: CanonicalToolCall,
        options: ToolOptions,
    ): ToolExecutionResult = knowledgeResult()

    private fun knowledgeResult() = ToolExecutionResult(
        content = "{\"results\":[{\"citation\":\"${citation.marker}\"}]}",
        ok = true,
        citations = listOf(citation),
    )
}
