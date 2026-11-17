package xyz.mek030399.tokenflow.data

import android.os.BatteryManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoToolsTest {
    private val instant = Instant.parse("2026-11-17T04:05:06.123Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val json = DirectApiTransport.defaultJson

    @Test
    fun timeIncludesCurrentLocalDateOffsetAndEpoch() = runTest {
        val tools = DeviceInfoTools(clock, { ZoneId.of("Asia/Hong_Kong") })
        val result = tools.execute(call(DEVICE_TIME_TOOL_NAME))
        val data = json.parseToJsonElement(result.content).jsonObject
        assertTrue(result.ok)
        assertEquals("2026-11-17T12:05:06.123+08:00", data.getValue("iso8601").jsonPrimitive.content)
        assertEquals(instant.toEpochMilli(), data.getValue("unix_time_ms").jsonPrimitive.long)
        assertEquals("Asia/Hong_Kong", data.getValue("timezone_id").jsonPrimitive.content)
        assertEquals(28_800, data.getValue("utc_offset_seconds").jsonPrimitive.int)
        assertEquals("tuesday", data.getValue("day_of_week").jsonPrimitive.content)
    }

    @Test
    fun timezoneIsReadAgainAfterTheSystemSettingChanges() = runTest {
        var currentZone = ZoneId.of("Asia/Hong_Kong")
        val tools = DeviceInfoTools(clock, { currentZone })
        assertTrue(tools.execute(call(DEVICE_TIMEZONE_TOOL_NAME)).content.contains("Asia/Hong_Kong"))
        currentZone = ZoneId.of("America/New_York")
        val data = json.parseToJsonElement(tools.execute(call(DEVICE_TIMEZONE_TOOL_NAME)).content).jsonObject
        assertEquals("America/New_York", data.getValue("timezone_id").jsonPrimitive.content)
        assertEquals(-18_000, data.getValue("utc_offset_seconds").jsonPrimitive.int)
        assertFalse(data.getValue("is_daylight_saving").jsonPrimitive.boolean)
        val summer = DeviceInfoTools(
            Clock.fixed(Instant.parse("2026-07-17T04:05:06Z"), ZoneOffset.UTC),
            { currentZone },
        )
        val summerData = json.parseToJsonElement(summer.execute(call(DEVICE_TIMEZONE_TOOL_NAME)).content).jsonObject
        assertTrue(summerData.getValue("is_daylight_saving").jsonPrimitive.boolean)
        assertEquals(-14_400, summerData.getValue("utc_offset_seconds").jsonPrimitive.int)
    }

    @Test
    fun batteryIsRefreshedOnEachCallAndDoesNotReadTimezone() = runTest {
        var percentage = 73
        val tools = DeviceInfoTools(
            clock,
            zoneId = { error("Battery does not need a timezone") },
            readBattery = { DeviceBatteryInfo(percentage, false, "discharging", "battery") },
        )
        val first = tools.execute(call(DEVICE_BATTERY_TOOL_NAME))
        assertTrue(first.ok)
        assertEquals(73, json.parseToJsonElement(first.content).jsonObject.getValue("percentage").jsonPrimitive.int)
        percentage = 52
        val second = tools.execute(call(DEVICE_BATTERY_TOOL_NAME))
        assertEquals(52, json.parseToJsonElement(second.content).jsonObject.getValue("percentage").jsonPrimitive.int)
    }

    @Test
    fun androidBatteryReadingsHandleScaleFullChargingAndMissingValues() {
        val charging = batteryInfoFromReadings(true, 3, 4, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_USB)!!
        assertEquals(75, charging.percentage)
        assertEquals(true, charging.charging)
        assertEquals("usb", charging.powerSource)
        val full = batteryInfoFromReadings(true, 100, 100, BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_AC)!!
        assertEquals(true, full.charging)
        assertEquals("full", full.status)
        val unknown = batteryInfoFromReadings(true, -1, -1, -1, -1)!!
        assertNull(unknown.percentage)
        assertNull(unknown.charging)
        assertEquals("unknown", unknown.status)
        assertNull(batteryInfoFromReadings(false, 0, 100, -1, -1))
        assertNull(batteryInfoFromReadings(true, 101, 100, -1, -1)!!.percentage)
    }

    @Test
    fun unavailableBatteryIsAnErrorAndUnknownFieldsRemainNull() = runTest {
        assertFalse(DeviceInfoTools(clock).execute(call(DEVICE_BATTERY_TOOL_NAME)).ok)
        val tools = DeviceInfoTools(clock, readBattery = { DeviceBatteryInfo(null, null, "unknown", "unknown") })
        val result = tools.execute(call(DEVICE_BATTERY_TOOL_NAME))
        assertTrue(result.ok)
        val data = json.parseToJsonElement(result.content).jsonObject
        assertEquals(JsonNull, data.getValue("percentage"))
        assertEquals(JsonNull, data.getValue("charging"))
    }

    @Test
    fun noArgumentToolsRejectMalformedOrUnexpectedParametersBeforeReading() = runTest {
        val tools = DeviceInfoTools(clock, readBattery = { error("Invalid arguments must not read the battery") })
        listOf("[]", "null", "", "{", "{\"percentage\":100}", " ".repeat(4_097)).forEach { arguments ->
            assertFalse(arguments, tools.execute(call(DEVICE_BATTERY_TOOL_NAME, arguments)).ok)
        }
        assertFalse(tools.execute(call("get_arbitrary_system_setting")).ok)
        tools.definitions().forEach { definition ->
            assertTrue(definition.parameters.getValue("properties").jsonObject.isEmpty())
            assertFalse(definition.parameters.getValue("additionalProperties").jsonPrimitive.boolean)
        }
    }

    @Test
    fun platformFailuresBecomeToolErrorsAndCancellationPropagates() = runTest {
        val unavailable = DeviceInfoTools(clock, readBattery = { throw SecurityException("restricted") })
        assertFalse(unavailable.execute(call(DEVICE_BATTERY_TOOL_NAME)).ok)
        val cancelled = DeviceInfoTools(clock, readBattery = { throw CancellationException("stopped") })
        try {
            cancelled.execute(call(DEVICE_BATTERY_TOOL_NAME))
            throw AssertionError("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("stopped", expected.message)
        }
    }

    private fun call(name: String, arguments: String = "{}") = CanonicalToolCall("device-1", name, arguments)
}
