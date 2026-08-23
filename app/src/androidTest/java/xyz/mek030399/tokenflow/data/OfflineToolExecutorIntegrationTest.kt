package xyz.mek030399.tokenflow.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineToolExecutorIntegrationTest {
    @Test
    fun missingExaKeyOmitsWebSearchSchemaEvenWhenSearchIsEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = SecretStore(context)
        val previousExaKey = secrets.read(SecretStore.EXA_KEY)

        try {
            secrets.remove(SecretStore.EXA_KEY)
            val executor = WebToolExecutor(
                secretStore = secrets,
                exaClient = ExaClient(),
                urlReader = UrlReader(context),
            )

            val names = executor.definitions(enableSearch = true, enableRead = false).map(ToolDefinition::name)

            assertEquals(listOf(CALCULATE_TOOL_NAME, CONVERT_UNITS_TOOL_NAME), names)
            assertFalse(names.contains("web_search"))
        } finally {
            if (previousExaKey == null) secrets.remove(SecretStore.EXA_KEY)
            else secrets.write(SecretStore.EXA_KEY, previousExaKey)
        }
    }

    @Test
    fun webToolExecutorAlwaysOffersAndDelegatesOfflineTools() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = WebToolExecutor(
            secretStore = SecretStore(context),
            exaClient = ExaClient(),
            urlReader = UrlReader(context),
        )

        assertEquals(
            listOf(CALCULATE_TOOL_NAME, CONVERT_UNITS_TOOL_NAME),
            executor.definitions(enableSearch = false, enableRead = false).map(ToolDefinition::name),
        )

        val result = executor.execute(
            call = CanonicalToolCall("calculate-1", CALCULATE_TOOL_NAME, "{\"expression\":\"6*7\"}"),
            options = ToolOptions(enableSearch = false, enableRead = false),
        )
        val payload = DirectApiTransport.defaultJson.parseToJsonElement(result.content).jsonObject

        assertTrue(result.ok)
        assertEquals("42", payload.getValue("result").jsonPrimitive.content)
    }
}
