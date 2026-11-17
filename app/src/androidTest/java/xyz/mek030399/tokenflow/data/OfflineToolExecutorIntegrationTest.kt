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

            assertEquals(listOf(CALCULATE_TOOL_NAME, CONVERT_UNITS_TOOL_NAME) + DEVICE_INFO_TOOL_NAMES, names)
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
            listOf(CALCULATE_TOOL_NAME, CONVERT_UNITS_TOOL_NAME) + DEVICE_INFO_TOOL_NAMES,
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

    @Test
    fun deviceToolsWorkInSessionsWithNetworkAndKnowledgeDisabled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = WebToolExecutor(
            secretStore = SecretStore(context),
            exaClient = ExaClient(),
            urlReader = UrlReader(context),
            deviceInfoTools = DeviceInfoTools.forAndroid(context),
        )
        val options = ToolOptions(enableSearch = false, enableRead = false, enableKnowledge = false)
        val session = executor.openSession(options)
        try {
            assertTrue(session.definitions.map(ToolDefinition::name).containsAll(DEVICE_INFO_TOOL_NAMES))
            val time = session.execute(CanonicalToolCall("time-1", DEVICE_TIME_TOOL_NAME, "{}"))
            assertTrue(time.ok)
            val zone = session.execute(CanonicalToolCall("zone-1", DEVICE_TIMEZONE_TOOL_NAME, "{}"))
            assertTrue(zone.ok)
            val battery = session.execute(CanonicalToolCall("battery-1", DEVICE_BATTERY_TOOL_NAME, "{}"))
            val payload = DirectApiTransport.defaultJson.parseToJsonElement(battery.content).jsonObject
            // A device without a battery must report unavailability, never a fabricated percentage.
            if (battery.ok) assertTrue(payload.containsKey("percentage"))
            else assertTrue(payload.containsKey("error"))
            assertEquals(null, DirectChatEngine(ModelGateway(DirectApiTransport()), executor).openSession(options, 0))
        } finally {
            session.close()
        }
    }
}
