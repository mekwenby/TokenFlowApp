package xyz.mek030399.tokenflow.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Read on demand; a null field means Android did not report that value. */
data class DeviceBatteryInfo(
    val percentage: Int?,
    val charging: Boolean?,
    val status: String,
    val powerSource: String,
)

class DeviceInfoTools(
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val readBattery: () -> DeviceBatteryInfo? = { null },
    private val json: Json = DirectApiTransport.defaultJson,
) {
    fun definitions(): List<ToolDefinition> = listOf(
        definition(
            DEVICE_TIME_TOOL_NAME,
            "Read the phone's current date and time, Unix time in milliseconds, timezone ID and UTC offset. " +
                "Call this for the current time rather than guessing from conversation timestamps. The phone clock may be user-set.",
        ),
        definition(
            DEVICE_TIMEZONE_TOOL_NAME,
            "Read the phone's currently configured timezone ID, display name, current UTC offset and daylight-saving status. " +
                "This is a system setting and does not determine the user's physical location.",
        ),
        definition(
            DEVICE_BATTERY_TOOL_NAME,
            "Read the phone's current battery percentage, charging status and power source. " +
                "Unknown readings are null or unknown; an unavailable battery produces an error.",
        ),
    )

    fun supports(name: String): Boolean = name in DEVICE_INFO_TOOL_NAMES

    suspend fun execute(call: CanonicalToolCall): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!supports(call.name)) return@withContext failure("Unknown device information tool")
        if (call.arguments.length > 4_096) return@withContext failure("Invalid tool arguments")
        val args = runCatching { json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
            ?: return@withContext failure("Tool arguments must be an empty JSON object")
        if (args.isNotEmpty()) return@withContext failure("This tool takes no parameters; use {}")
        try {
            val now = clock.instant()
            val payload = when (call.name) {
                DEVICE_TIME_TOOL_NAME -> buildJsonObject {
                    val zone = zoneId()
                    val local = now.atZone(zone)
                    put("iso8601", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(local))
                    put("unix_time_ms", now.toEpochMilli())
                    put("timezone_id", zone.id)
                    put("utc_offset", local.offset.id)
                    put("utc_offset_seconds", local.offset.totalSeconds)
                    put("day_of_week", local.dayOfWeek.name.lowercase(Locale.ROOT))
                }
                DEVICE_TIMEZONE_TOOL_NAME -> buildJsonObject {
                    val zone = zoneId()
                    val offset = zone.rules.getOffset(now)
                    put("timezone_id", zone.id)
                    put("display_name", zone.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("utc_offset", offset.id)
                    put("utc_offset_seconds", offset.totalSeconds)
                    put("is_daylight_saving", zone.rules.isDaylightSavings(now))
                    put("observed_at", now.toString())
                }
                else -> {
                    val battery = readBattery() ?: return@withContext failure("Battery information is unavailable on this device")
                    buildJsonObject {
                        put("percentage", battery.percentage?.takeIf { it in 0..100 }?.let(::JsonPrimitive) ?: JsonNull)
                        put("charging", battery.charging?.let(::JsonPrimitive) ?: JsonNull)
                        put("status", battery.status)
                        put("power_source", battery.powerSource)
                        put("observed_at", now.toString())
                    }
                }
            }
            ToolExecutionResult(payload.toString(), true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure("Device information is unavailable")
        }
    }

    private fun definition(name: String, purpose: String) = ToolDefinition(
        name = name,
        description = "$purpose Reads locally without network access or extra Android permissions. " +
            "The result is included in the ongoing model conversation. Takes no parameters.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("required", buildJsonArray {})
            put("additionalProperties", false)
        },
    )

    private fun failure(message: String) = ToolExecutionResult(
        buildJsonObject { put("error", message) }.toString(),
        false,
    )

    companion object {
        fun forAndroid(context: Context, json: Json = DirectApiTransport.defaultJson): DeviceInfoTools {
            val appContext = context.applicationContext
            return DeviceInfoTools(
                json = json,
                readBattery = {
                    appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
                        batteryInfoFromReadings(
                            present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true),
                            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
                        )
                    }
                },
            )
        }
    }
}

internal fun batteryInfoFromReadings(present: Boolean, level: Int, scale: Int, status: Int, plugged: Int): DeviceBatteryInfo? {
    if (!present) return null
    val statusName = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        else -> "unknown"
    }
    return DeviceBatteryInfo(
        percentage = if (scale > 0 && level in 0..scale) (level.toDouble() * 100 / scale).roundToInt() else null,
        charging = when (statusName) {
            "charging", "full" -> true
            "discharging", "not_charging" -> false
            else -> null
        },
        status = statusName,
        powerSource = when (plugged) {
            0 -> "battery"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
            else -> "unknown"
        },
    )
}

internal const val DEVICE_TIME_TOOL_NAME = "get_device_time"
internal const val DEVICE_TIMEZONE_TOOL_NAME = "get_device_timezone"
internal const val DEVICE_BATTERY_TOOL_NAME = "get_device_battery"
internal val DEVICE_INFO_TOOL_NAMES = setOf(DEVICE_TIME_TOOL_NAME, DEVICE_TIMEZONE_TOOL_NAME, DEVICE_BATTERY_TOOL_NAME)
