package xyz.mek030399.tokenflow.data

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityRulesTest {
    @Test
    fun providerUrlsRequireCleanHttpsRoots() {
        assertEquals("https://api.example.com/v1", ProviderValidator.normalizeBaseUrl("https://api.example.com/v1/"))
        assertThrows(ConfigurationException::class.java) { ProviderValidator.normalizeBaseUrl("http://api.example.com/v1") }
        assertThrows(ConfigurationException::class.java) { ProviderValidator.normalizeBaseUrl("https://user@example.com/v1") }
        assertThrows(ConfigurationException::class.java) { ProviderValidator.normalizeBaseUrl("https://api.example.com/v1?q=1") }
        assertThrows(ConfigurationException::class.java) { ProviderValidator.normalizeBaseUrl("https://api.example.com/v1#fragment") }
    }

    @Test
    fun urlReaderRejectsPrivateReservedAndNonStandardPorts() {
        listOf("127.0.0.1", "10.0.0.1", "169.254.1.2", "192.168.1.2", "203.0.113.1", "::1", "fc00::1").forEach {
            assertFalse(it, SafeUrlValidator.isPublicAddress(InetAddress.getByName(it)))
        }
        assertTrue(SafeUrlValidator.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertThrows(ConfigurationException::class.java) { SafeUrlValidator.parseAndResolve("https://example.com:8443/page") }
        assertThrows(ConfigurationException::class.java) { SafeUrlValidator.parseAndResolve("https://user@example.com/page") }
        assertThrows(ConfigurationException::class.java) { SafeUrlValidator.parseAndResolve("http://example.com/page") }
    }

    @Test
    fun injectedDnsStillRejectsEmptyAndMixedPrivateResults() {
        val publicAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        val privateAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        assertThrows(ConfigurationException::class.java) {
            SafeUrlValidator.parseAndResolve("https://example.test/page") { emptyList() }
        }
        assertThrows(ConfigurationException::class.java) {
            SafeUrlValidator.parseAndResolve("https://example.test/page") {
                listOf(publicAddress, privateAddress)
            }
        }
    }

    @Test
    fun promptLibraryContainsTwelveStableRolesAndRuntimeSafetyInstructions() {
        assertEquals(12, SystemPrompts.templates.size)
        assertEquals(12, SystemPrompts.templates.map { it.id }.distinct().size)
        val prompt = SystemPrompts.compose(
            customPrompt = "Be a developer",
            nickname = "User",
            enableSearch = true,
            enableRead = true,
            timeZone = "Asia/Shanghai",
        )
        assertTrue(prompt.contains("Asia/Shanghai"))
        assertTrue(prompt.contains("- calculate:"))
        assertTrue(prompt.contains("- convert_units:"))
        assertTrue(prompt.contains("web_search"))
        assertTrue(prompt.contains("read_url"))
        assertTrue(prompt.contains("untrusted data"))
    }

    @Test
    fun zeroToolBudgetPromptDoesNotAdvertiseOfflineOrOptionalTools() {
        val prompt = SystemPrompts.compose(
            customPrompt = "",
            nickname = "",
            enableSearch = false,
            enableRead = false,
            timeZone = "UTC",
            offlineToolsAvailable = false,
        )

        assertTrue(prompt.contains("No callable tools are available"))
        assertFalse(prompt.contains("- calculate:"))
        assertFalse(prompt.contains("- convert_units:"))
        assertFalse(prompt.contains("- web_search:"))
        assertFalse(prompt.contains("- read_url:"))
        assertFalse(prompt.contains("- search_knowledge:"))
    }

    @Test
    fun localKnowledgeModeExplainsRetrievalFallbackSafetyConflictsAndCitations() {
        val prompt = SystemPrompts.compose(
            customPrompt = "",
            nickname = "",
            enableSearch = true,
            enableRead = true,
            timeZone = "UTC",
            enableKnowledge = true,
            knowledgeToolAvailable = true,
        )

        assertTrue(prompt.contains("- search_knowledge:"))
        assertTrue(prompt.contains("Start with any prefetched <local_knowledge>"))
        assertTrue(prompt.contains("[[KB:<chunkId>]]"))
        assertTrue(prompt.contains("copy its exact marker inline and unchanged"))
        assertTrue(prompt.contains("Never invent a KB marker"))
        assertTrue(prompt.contains("rewrite it as #0/#1"))
        assertTrue(prompt.contains("one focused second search"))
        assertTrue(prompt.contains("before falling back to the web"))
        assertTrue(prompt.contains("untrusted reference data"))
        assertTrue(prompt.contains("exact document or reference label"))
        assertTrue(prompt.contains("When sources conflict"))
        assertTrue(prompt.contains("general model knowledge"))
        assertTrue(prompt.contains("never invent a local hit or source"))
    }

    @Test
    fun knowledgeModeAndToolAvailabilityAreIndependent() {
        val prefetchedOnly = SystemPrompts.compose(
            customPrompt = "",
            nickname = "",
            enableSearch = false,
            enableRead = false,
            timeZone = "UTC",
            enableKnowledge = true,
            knowledgeToolAvailable = false,
        )
        val toolOnly = SystemPrompts.compose(
            customPrompt = "",
            nickname = "",
            enableSearch = false,
            enableRead = false,
            timeZone = "UTC",
            enableKnowledge = false,
            knowledgeToolAvailable = true,
        )

        assertTrue(prefetchedOnly.contains("Local knowledge mode:"))
        assertTrue(prefetchedOnly.contains("search_knowledge is unavailable"))
        assertTrue(prefetchedOnly.contains("general model knowledge"))
        assertFalse(prefetchedOnly.contains("- search_knowledge:"))
        assertFalse(toolOnly.contains("Local knowledge mode:"))
        assertTrue(toolOnly.contains("- search_knowledge:"))
    }
}
