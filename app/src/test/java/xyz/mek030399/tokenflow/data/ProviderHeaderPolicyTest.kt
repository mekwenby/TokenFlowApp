package xyz.mek030399.tokenflow.data

import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHeaderPolicyTest {
    @Test
    fun normalizationTrimsNamesDropsEmptyRowsAndPreservesValuesAndIdentity() {
        val row = header("  X-Custom  ", "  exact value  ")
        val rows = ProviderHeaderPolicy.normalize(listOf(ProviderRequestHeader(), row))

        assertEquals(listOf(row.copy(name = "X-Custom")), rows)
        assertEquals(row.id, rows.single().id)
    }

    @Test
    fun enabledDuplicatesAreRejectedIgnoringCaseWhileDisabledRowsRemainEditable() {
        val first = header("User-Agent", "custom")
        val duplicate = header(" user-agent ", "other")
        assertFailure("Enabled request header names must be unique") {
            ProviderHeaderPolicy.normalize(listOf(first, duplicate))
        }
        assertEquals(listOf(first), ProviderHeaderPolicy.enabled(listOf(first, duplicate.copy(enabled = false))))
        assertEquals(2, ProviderHeaderPolicy.normalize(listOf(first, duplicate.copy(enabled = false))).size)
    }

    @Test
    fun importedRowIdsMustRemainNonblankAndUniqueEvenForDisabledRows() {
        val first = header("X-One", "one").copy(id = "row-id")
        assertFailure("Request header row IDs must be unique") {
            ProviderHeaderPolicy.normalize(listOf(first, header("X-Two", "two", enabled = false).copy(id = " row-id ")))
        }
        assertFailure("Invalid request header row ID") {
            ProviderHeaderPolicy.normalize(listOf(header("X-Two", "two", enabled = false).copy(id = " ")))
        }
        assertTrue(ProviderHeaderPolicy.normalize(listOf(ProviderRequestHeader(id = ""))).isEmpty())
        assertEquals("row-id", ProviderHeaderPolicy.normalize(listOf(first.copy(id = " row-id "))).single().id)
    }

    @Test
    fun transportManagedHeadersCannotBeEnabled() {
        listOf("Host", "Content-Length", "Content-Type", "Connection", "Transfer-Encoding", "Content-Encoding",
            "Accept-Encoding", "TE", "Trailer", "Upgrade", "Proxy-Authorization", "Keep-Alive").forEach { name ->
            assertFailure("This request header is managed by the HTTP transport") {
                ProviderHeaderPolicy.normalize(listOf(header(name, "fixture-value")))
            }
        }
    }

    @Test
    fun invalidNamesAndValuesUseGenericErrorsWithoutEchoingInput() {
        listOf("Bad Header", ":authority", "X-Test\r\nfixture-secret", "Authorization: Bearer fixture-secret",
            "\r\nX-Test", "X-Test\r\n", "X-Test\u0000", " \tX-Test ", "", "头").forEach { name ->
            assertFailure("Invalid request header name") {
                ProviderHeaderPolicy.normalize(listOf(header(name, "fixture-secret")))
            }
        }
        listOf("fixture-secret\r\nX-Injected: value", "fixture-secret\u0000", "fixture-secret中文").forEach { value ->
            assertFailure("Invalid request header value") {
                ProviderHeaderPolicy.normalize(listOf(header("X-Custom", value)))
            }
        }
    }

    @Test
    fun disabledInvalidRowsDoNotBlockAuthenticationOrSending() {
        val invalid = listOf(
            header("Host", "fixture-secret"),
            header("Bad Header", "fixture-secret\n"),
            header("Authorization", ""),
        ).map { it.copy(enabled = false) }

        assertEquals(invalid, ProviderHeaderPolicy.normalize(invalid))
        assertTrue(ProviderHeaderPolicy.enabled(invalid).isEmpty())
        assertFalse(ProviderHeaderPolicy.hasCustomAuthentication(invalid))
        ProviderHeaderPolicy.validateAuthentication("normal-key", invalid)
    }

    @Test
    fun eachCustomAuthenticationFamilyCanReplaceTheRegularApiKey() {
        listOf("Authorization", "X-API-Key", "api-key").forEach { name ->
            val rows = listOf(header(name, "custom-auth-value"))
            assertTrue(ProviderHeaderPolicy.hasCustomAuthentication(rows))
            ProviderHeaderPolicy.validateAuthentication("", rows)
            ProviderHeaderPolicy.validateAuthentication("unused-invalid-key\n", rows)
        }
        assertFailure("Custom authentication header values must not be blank") {
            ProviderHeaderPolicy.validateAuthentication("normal-key", listOf(header("Authorization", "  ")))
        }
    }

    @Test
    fun partiallyEditedRowsCannotCrashCustomAuthenticationHint() {
        assertTrue(ProviderHeaderPolicy.hasCustomAuthentication(listOf(header("Authorization", "Bearer fixture"), header("Bad Header", "\n"))))
        assertFalse(ProviderHeaderPolicy.hasCustomAuthentication(listOf(header("Authorization", "fixture\n"))))
        assertFalse(ProviderHeaderPolicy.hasCustomAuthentication(listOf(header("\nAuthorization", "fixture"))))
        assertFalse(ProviderHeaderPolicy.hasCustomAuthentication(listOf(header("Authorization", " "))))
        assertFalse(ProviderHeaderPolicy.hasCustomAuthentication(listOf(header("X-Other", "Bearer fixture"))))
    }

    @Test
    fun regularAuthenticationIsRequiredAndValidatedWhenNoCustomAuthIsActive() {
        assertFailure("API key is required") {
            ProviderHeaderPolicy.validateAuthentication("", listOf(header("X-Trace", "trace")))
        }
        assertFailure("Invalid API key header value") {
            ProviderHeaderPolicy.validateAuthentication("fixture-secret\n", emptyList())
        }
        assertFailure("API key is required") {
            ProviderHeaderPolicy.validateAuthentication("", listOf(header("Authorization", "fixture", enabled = false)))
        }
    }

    @Test
    fun protocolAndClientIdentificationHeadersRemainUserOverridable() {
        val rows = listOf(header("User-Agent", "custom-client"), header("Accept", "custom/stream"),
            header("anthropic-version", "custom-version"), header("X-Client-Request-Id", "custom-request"),
            header("anthropic-beta", "custom-feature"))
        assertEquals(rows, ProviderHeaderPolicy.enabled(rows))
    }

    @Test
    fun applyingTemplateReplacesOwnedRowsAndManualCollisionsButKeepsOtherManualAndAuthRows() {
        val auth = header("Authorization", "Bearer fixture")
        val ownedAuth = header("X-API-Key", "keep-auth").copy(templateId = "old-template")
        val manual = header("X-Manual", "keep")
        val disabled = header("X-Disabled", "keep", enabled = false)
        val previous = header("X-Old-Template", "old").copy(templateId = "old-template")
        val collision = header(" user-agent ", "manual-ua")
        val applied = ProviderHeaderTemplates.apply(listOf(auth, ownedAuth, manual, disabled, previous, collision), ProviderHeaderTemplate.PI)

        assertEquals(listOf(auth, manual, disabled), applied.filter { it.templateId == null })
        assertTrue(ownedAuth in applied)
        assertEquals("pi", applied.single { it.name == "User-Agent" }.value)
        assertEquals("pi", applied.last().templateId)
        assertTrue(applied.last().enabled)
        assertTrue(applied.none { it.id == previous.id || it.id == collision.id })
    }

    @Test
    fun templatesHaveOnlyTheAgreedStaticHeadersAndSavedSessionIdentifiersStayStable() {
        val claude = ProviderHeaderTemplates.apply(emptyList(), ProviderHeaderTemplate.CLAUDE_CODE)
        val claudeValues = claude.associate { it.name to it.value }
        assertEquals(setOf("User-Agent", "x-app", "anthropic-version", "x-claude-code-session-id"), claudeValues.keys)
        assertEquals("claude-cli/2.1.327 (external, cli)", claudeValues["User-Agent"])
        assertEquals("cli", claudeValues["x-app"])
        assertEquals("2023-06-01", claudeValues["anthropic-version"])
        assertEquals(claudeValues["x-claude-code-session-id"], UUID.fromString(claudeValues.getValue("x-claude-code-session-id")).toString())

        val openCode = ProviderHeaderTemplates.apply(emptyList(), ProviderHeaderTemplate.OPENCODE)
        val values = openCode.associate { it.name to it.value }
        assertEquals(setOf("User-Agent", "x-opencode-client", "x-opencode-project", "x-opencode-session"), values.keys)
        assertEquals("opencode/latest/1.17.11/cli", values["User-Agent"])
        assertEquals("cli", values["x-opencode-client"])
        assertEquals("global", values["x-opencode-project"])
        assertTrue(values.getValue("x-opencode-session").matches(Regex("ses_[0-9a-f]{64}")))
        val json = ConfigArchiveCodec.defaultJson
        assertEquals(openCode, json.decodeFromString<List<ProviderRequestHeader>>(json.encodeToString(openCode)))
        assertEquals(openCode, ProviderHeaderPolicy.normalize(openCode))
        assertNotEquals(values["x-opencode-session"],
            ProviderHeaderTemplates.apply(openCode, ProviderHeaderTemplate.OPENCODE).single { it.name == "x-opencode-session" }.value)
        assertEquals(listOf("User-Agent" to "pi"),
            ProviderHeaderTemplates.apply(openCode, ProviderHeaderTemplate.PI).map { it.name to it.value })
    }

    @Test
    fun headerDebugStringDoesNotRevealNameOrValueContent() {
        val row = header("Authorization: fixture-secret", "fixture-secret")
        assertFalse(row.toString().contains("fixture-secret"))
    }

    private fun header(name: String, value: String, enabled: Boolean = true) =
        ProviderRequestHeader(name = name, value = value, enabled = enabled)

    private fun assertFailure(message: String, action: () -> Unit) {
        assertEquals(message, assertThrows(ConfigurationException::class.java) { action() }.message)
    }
}
