package xyz.mek030399.tokenflow.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mek030399.tokenflow.R
import xyz.mek030399.tokenflow.data.ConfigurationException
import xyz.mek030399.tokenflow.data.ProviderRequestHeader

internal object ProviderHeaderJson {
    private val json = Json { prettyPrint = true }
    private val fields = setOf("name", "value", "enabled")

    @Serializable
    private class Entry(val name: String, val value: String, val enabled: Boolean = true) {
        fun matches(row: ProviderRequestHeader): Boolean = name == row.name && value == row.value
    }

    fun encode(rows: List<ProviderRequestHeader>): String = json.encodeToString(
        rows.map { Entry(it.name, it.value, it.enabled) },
    )

    fun decode(text: String, previous: List<ProviderRequestHeader>): List<ProviderRequestHeader> {
        val array = try {
            json.parseToJsonElement(text) as? JsonArray ?: invalidJson()
        } catch (_: SerializationException) {
            invalidJson()
        }
        val entries = array.map { element ->
            val entry = element as? JsonObject ?: invalidJson()
            if (!fields.containsAll(entry.keys)) invalidJson()
            Entry(
                name = entry.requiredString("name"),
                value = entry.requiredString("value"),
                enabled = when (val enabled = entry["enabled"]) {
                    null -> true
                    JsonPrimitive(true) -> true
                    JsonPrimitive(false) -> false
                    else -> invalidJson()
                },
            )
        }
        val remaining = previous.toMutableList()
        // Match unchanged entries before toggled entries, including repeated disabled headers.
        val unchanged = entries.map { entry ->
            val index = remaining.indexOfFirst { entry.matches(it) && entry.enabled == it.enabled }
            if (index >= 0) remaining.removeAt(index) else null
        }
        return entries.mapIndexed { index, entry ->
            unchanged[index] ?: run {
                val toggledIndex = remaining.indexOfFirst(entry::matches)
                if (toggledIndex >= 0) remaining.removeAt(toggledIndex).copy(enabled = entry.enabled)
                else ProviderRequestHeader(name = entry.name, value = entry.value, enabled = entry.enabled)
            }
        }
    }

    private fun JsonObject.requiredString(field: String): String =
        (get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidJson()

    private fun invalidJson(): Nothing = throw ConfigurationException("Invalid request headers JSON")
}

internal fun providerHeaderFailureText(message: String?): UiText.Resource? = when (message) {
    "Invalid request header name" -> UiText.Resource(R.string.provider_header_invalid_name)
    "Invalid request header value" -> UiText.Resource(R.string.provider_header_invalid_value)
    "This request header is managed by the HTTP transport" -> UiText.Resource(R.string.provider_header_managed)
    "Enabled request header names must be unique" -> UiText.Resource(R.string.provider_header_duplicate)
    "Custom authentication header values must not be blank" -> UiText.Resource(R.string.provider_header_auth_blank)
    "API key is required" -> UiText.Resource(R.string.provider_header_api_key_required)
    "Invalid API key header value" -> UiText.Resource(R.string.provider_header_invalid_api_key)
    "Saved provider request headers are invalid" -> UiText.Resource(R.string.provider_header_saved_invalid)
    "Invalid request header row ID" -> UiText.Resource(R.string.provider_header_invalid_id)
    "Request header row IDs must be unique" -> UiText.Resource(R.string.provider_header_duplicate_id)
    "Invalid request headers JSON" -> UiText.Resource(R.string.provider_header_json_invalid)
    else -> null
}
