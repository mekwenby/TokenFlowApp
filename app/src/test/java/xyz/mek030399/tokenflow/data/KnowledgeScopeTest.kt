package xyz.mek030399.tokenflow.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class KnowledgeScopeTest {
    @Test fun oldConversationsDefaultToAllAndSelectedRangeSurvivesSerializationAndEntityConversion() {
        val legacy = ConfigArchiveCodec.defaultJson.decodeFromString<Conversation>("{\"id\":\"legacy\"}")
        assertEquals(KnowledgeScope(), legacy.knowledgeScope)
        val selected = legacy.copy(knowledgeScope = KnowledgeScope(KnowledgeScopeMode.SELECTED, listOf("private-document")))
        val restored = ConfigArchiveCodec.defaultJson.decodeFromString<Conversation>(ConfigArchiveCodec.defaultJson.encodeToString(selected))
        assertEquals(selected.knowledgeScope, restored.knowledgeScope)
        assertEquals(selected.knowledgeScope, selected.toEntity().toDomain().knowledgeScope)
        val empty = selected.copy(knowledgeScope = KnowledgeScope(KnowledgeScopeMode.SELECTED))
        assertEquals(KnowledgeScopeMode.SELECTED, empty.toEntity().toDomain().knowledgeScope.mode)
        assertFalse(empty.toEntity().toDomain().knowledgeScope.includes("private-document"))
    }
}
