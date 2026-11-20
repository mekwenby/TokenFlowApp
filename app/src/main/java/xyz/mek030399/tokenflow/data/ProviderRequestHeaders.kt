package xyz.mek030399.tokenflow.data

import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers

private val providerAuthenticationNames = setOf("authorization", "x-api-key", "api-key")

@Serializable
data class ProviderRequestHeader(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val value: String = "",
    val enabled: Boolean = true,
    @SerialName("template_id") val templateId: String? = null,
) {
    override fun toString(): String =
        "ProviderRequestHeader(id=$id, name=[redacted], value=[redacted], enabled=$enabled, templateId=$templateId)"
}

object ProviderHeaderPolicy {
    private val headerNamePattern = Regex("[!#$%&'*+.^_\\x60|~0-9A-Za-z-]+")
    private val protectedNames = setOf(
        "host", "content-length", "content-type", "connection", "transfer-encoding",
        "content-encoding", "accept-encoding", "te", "trailer", "upgrade",
        "proxy-authorization", "keep-alive",
    )

    fun normalize(rows: List<ProviderRequestHeader>): List<ProviderRequestHeader> {
        if (rows.any { row -> row.enabled && row.name.any { Character.isISOControl(it) } }) {
            throw ConfigurationException("Invalid request header name")
        }
        val normalized = rows.map { it.copy(id = it.id.trim(), name = it.name.trim()) }
            .filterNot { it.name.isEmpty() && it.value.isBlank() }
        val ids = mutableSetOf<String>()
        normalized.forEach { row ->
            if (row.id.isEmpty()) throw ConfigurationException("Invalid request header row ID")
            if (!ids.add(row.id)) throw ConfigurationException("Request header row IDs must be unique")
        }
        val names = mutableSetOf<String>()
        normalized.filter(ProviderRequestHeader::enabled).forEach { row ->
            validateName(row.name)
            validateValue(row.value)
            val name = row.name.lowercase(Locale.ROOT)
            if (name in protectedNames) throw ConfigurationException("This request header is managed by the HTTP transport")
            if (!names.add(name)) throw ConfigurationException("Enabled request header names must be unique")
            if (name in providerAuthenticationNames && row.value.isBlank()) {
                throw ConfigurationException("Custom authentication header values must not be blank")
            }
        }
        return normalized
    }

    fun enabled(rows: List<ProviderRequestHeader>): List<ProviderRequestHeader> =
        normalize(rows).filter(ProviderRequestHeader::enabled)

    /** Safe for partially edited rows: save/send validation reports invalid configuration separately. */
    fun hasCustomAuthentication(rows: List<ProviderRequestHeader>): Boolean = rows.any { row ->
        row.enabled && row.name.none { Character.isISOControl(it) } &&
            row.name.trim().lowercase(Locale.ROOT) in providerAuthenticationNames &&
            row.value.isNotBlank() && runCatching {
                Headers.Builder().add(row.name.trim(), row.value).build()
            }.isSuccess
    }

    fun validateAuthentication(apiKey: String, rows: List<ProviderRequestHeader>) {
        val active = enabled(rows)
        if (hasCustomAuthentication(active)) return
        if (apiKey.isBlank()) throw ConfigurationException("API key is required")
        if (runCatching { Headers.Builder().add("Authorization", apiKey).build() }.isFailure) {
            throw ConfigurationException("Invalid API key header value")
        }
    }

    private fun validateName(name: String) {
        if (!headerNamePattern.matches(name) || runCatching { Headers.Builder().add(name, "").build() }.isFailure) {
            throw ConfigurationException("Invalid request header name")
        }
    }

    private fun validateValue(value: String) {
        if (runCatching { Headers.Builder().add("X-Header-Validation", value).build() }.isFailure) {
            throw ConfigurationException("Invalid request header value")
        }
    }
}

@Serializable
enum class ProviderHeaderTemplate(val id: String) {
    CLAUDE_CODE("claude_code"),
    OPENCODE("opencode"),
    PI("pi"),
}

object ProviderHeaderTemplates {
    private val random = SecureRandom()

    fun apply(rows: List<ProviderRequestHeader>, template: ProviderHeaderTemplate): List<ProviderRequestHeader> {
        val values = when (template) {
            ProviderHeaderTemplate.CLAUDE_CODE -> listOf(
                "User-Agent" to "claude-cli/2.1.327 (external, cli)",
                "x-app" to "cli",
                "anthropic-version" to "2023-06-01",
                "x-claude-code-session-id" to UUID.randomUUID().toString(),
            )
            ProviderHeaderTemplate.OPENCODE -> listOf(
                "User-Agent" to "opencode/latest/1.17.11/cli",
                "x-opencode-client" to "cli",
                "x-opencode-project" to "global",
                "x-opencode-session" to "ses_" + randomSessionId(),
            )
            ProviderHeaderTemplate.PI -> listOf("User-Agent" to "pi")
        }
        val templateNames = values.map { it.first.lowercase(Locale.ROOT) }.toSet()
        return rows.filter { row ->
            val name = row.name.trim().lowercase(Locale.ROOT)
            name in providerAuthenticationNames || (row.templateId == null && name !in templateNames)
        } +
            values.map { (name, value) -> ProviderRequestHeader(name = name, value = value, templateId = template.id) }
    }

    private fun randomSessionId(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return bytes.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }
}
