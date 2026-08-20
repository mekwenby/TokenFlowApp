package xyz.mek030399.tokenflow.data

import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCalculationToolsTest {
    private val json = DirectApiTransport.defaultJson
    private val tools = OfflineCalculationTools(json)

    @Test
    fun definitionsExposeStrictSchemasAndEveryCanonicalUnit() {
        val definitions = tools.definitions()

        assertEquals(listOf(CALCULATE_TOOL_NAME, CONVERT_UNITS_TOOL_NAME), definitions.map(ToolDefinition::name))
        val calculate = definitions.first().parameters
        assertFalse(calculate.getValue("additionalProperties").jsonPrimitive.boolean)
        val expression = calculate.getValue("properties").jsonObject.getValue("expression").jsonObject
        assertEquals(1, expression.getValue("minLength").jsonPrimitive.int)
        assertEquals(256, expression.getValue("maxLength").jsonPrimitive.int)

        val conversionProperties = definitions.last().parameters.getValue("properties").jsonObject
        assertEquals("number", conversionProperties.getValue("value").jsonObject.getValue("type").jsonPrimitive.content)
        val fromUnits = conversionProperties.getValue("from_unit").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        val toUnits = conversionProperties.getValue("to_unit").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(EXPECTED_UNITS, fromUnits)
        assertEquals(EXPECTED_UNITS, toUnits)
    }

    @Test
    fun calculatesWithDecimal128PrecedenceAndScientificFunctions() = runTest {
        assertCalculation("2 + 3 * 4", "14")
        assertCalculation("0.1 + 0.2", "0.3")
        assertCalculation("1e3 + 2 ^ 3", "1008")
        assertCalculation(
            "ABS(-2)+SQRT(9)+LOG(E)+LOG10(100)+MIN(4,2)+MAX(4,2)+SUM(1,2,3)+AVERAGE(2,4)+ROUND(2.345,2)+FLOOR(2.9)+CEILING(2.1)",
            "30.34",
        )
        assertCalculation("PERCENT(13) + MOD(10,3)", "1.13")
        assertCalculation("percent(25) + mod(17,5)", "2.25")
    }

    @Test
    fun publicTrigonometricFunctionsUseRadians() = runTest {
        assertClose("1", calculationValue("SIN(PI/2)"), "1E-15")
        assertClose("-1", calculationValue("COS(PI)"), "1E-15")
        assertCalculation("TAN(0)", "0")
        assertClose("1.5707963267948966", calculationValue("ASIN(1)"), "1E-15")
        assertCalculation("ACOS(1)", "0")
        assertClose("0.7853981633974483", calculationValue("ATAN(1)"), "1E-15")
        assertClose("1.5707963267948966", calculationValue("ATAN2(1,0)"), "1E-15")
        assertCalculation("SINH(0)+COSH(0)+TANH(0)", "1")
        assertClose("3.141592653589793", calculationValue("RAD(180)"), "1E-15")
        assertClose("180", calculationValue("DEG(PI)"), "1E-12")
    }

    @Test
    fun formatsPlainScientificAndNegativeZeroConsistently() = runTest {
        assertCalculation("1e-6", "0.000001")
        assertCalculation("1e-7", "1E-7")
        assertCalculation("1e20", "100000000000000000000")
        assertCalculation("1e21", "1E+21")
        assertCalculation("-0", "0")
        assertCalculation("1/3", "0.3333333333333333333333333333333333")
    }

    @Test
    fun calculationRejectsAmbiguousOrUnsafeSyntaxAndBoundsErrors() = runTest {
        listOf(
            "" to "must not be blank",
            "   " to "must not be blank",
            "2 % 1" to "Use MOD",
            "x + 1" to "Unknown identifier",
            "RANDOM()" to "Unknown identifier",
            "'text'" to "unsupported character",
            "[1,2]" to "unsupported character",
            "1 == 1" to "unsupported character",
            "5!" to "unsupported character",
            "SIN PI" to "followed by parentheses",
            "PI(2)" to "Unknown identifier",
            "1e1001" to "exponent is too large",
            "2^(500+501)" to "Power exponent",
            "ROUND(1.2,101)" to "ROUND scale",
            "ROUND(1.2,1.5)" to "ROUND scale",
            "MOD(1,0)" to "must not be zero",
            "SQRT(-1)" to "must not be negative",
            "1/0" to "zero",
            "(-1)^0.5" to "not finite",
            "π + 1" to "ASCII",
            "(".repeat(17) + "1" + ")".repeat(17) to "nested too deeply",
            "+".repeat(129) + "1" to "too complex",
            "9".repeat(65) to "Number literal is too long",
            "1".repeat(257) to "expression is too long",
        ).forEach { (expression, expectedError) ->
            val result = calculate(expression)
            assertFalse("Expected failure for $expression", result.ok)
            val error = errorValue(result)
            assertTrue("Expected '$expectedError' in '$error'", error.contains(expectedError, ignoreCase = true))
            assertTrue(error.length <= 200)
        }
    }

    @Test
    fun calculationRejectsMalformedArgumentsAndUnknownTools() = runTest {
        val invalidJson = tools.execute(CanonicalToolCall("id", CALCULATE_TOOL_NAME, "{"))
        val wrongType = execute(CALCULATE_TOOL_NAME, buildJsonObject { put("expression", 42) })
        val extraProperty = execute(CALCULATE_TOOL_NAME, buildJsonObject {
            put("expression", "1+1")
            put("extra", true)
        })
        val unknown = execute("offline_unknown", buildJsonObject {})

        listOf(invalidJson, wrongType, extraProperty, unknown).forEach { assertFalse(it.ok) }
        assertEquals("Invalid tool arguments", errorValue(invalidJson))
        assertTrue(errorValue(wrongType).contains("must be a string"))
        assertEquals("Invalid tool arguments", errorValue(extraProperty))
        assertTrue(errorValue(unknown).contains("Unknown tool"))
    }

    @Test
    fun convertsRepresentativeUnitsAcrossEveryDimension() = runTest {
        assertConversion("1", "km", "m", "1000")
        assertConversion("1", "lb", "kg", "0.45359237")
        assertConversion("32", "F", "C", "0")
        assertConversion("1", "acre", "m2", "4046.8564224")
        assertConversion("1", "gal_us", "L", "3.785411784")
        assertConversion("1", "gal_imp", "L", "4.54609")
        assertConversion("36", "km/h", "m/s", "10")
        assertConversion("1", "d", "h", "24")
        assertConversion("1", "wk", "d", "7")
        assertConversion("1", "atm", "kPa", "101.325")
        assertConversion("1", "kcal", "J", "4184")
        assertConversion("1", "hp", "W", "745.69987158227022")
        assertClose(
            "3.141592653589793238462643383279503",
            conversionValue("180", "deg", "rad"),
            "1E-33",
        )
        assertConversion("1", "B", "bit", "8")
    }

    @Test
    fun distinguishesUsImperialDecimalIecBitsAndBytes() = runTest {
        val usGallonsInLitres = BigDecimal(conversionValue("1", "gal_us", "L"))
        val imperialGallonsInLitres = BigDecimal(conversionValue("1", "gal_imp", "L"))

        assertTrue(imperialGallonsInLitres > usGallonsInLitres)
        assertConversion("1", "MB", "B", "1000000")
        assertConversion("1", "MiB", "B", "1048576")
        assertConversion("8", "bit", "B", "1")
        assertConversion("1", "GB", "Gbit", "8")
    }

    @Test
    fun temperatureEnforcesAbsoluteZero() = runTest {
        assertConversion("-273.15", "C", "K", "0")
        assertConversion("-459.67", "F", "K", "0")

        listOf(
            convert("-273.1500001", "C", "K"),
            convert("-459.670001", "F", "C"),
            convert("-0.000001", "K", "F"),
        ).forEach { result ->
            assertFalse(result.ok)
            assertTrue(errorValue(result).contains("absolute zero"))
        }
    }

    @Test
    fun conversionRejectsBadTypesUnitsDimensionsAndExtraArguments() = runTest {
        val stringNumber = execute(CONVERT_UNITS_TOOL_NAME, buildJsonObject {
            put("value", "1")
            put("from_unit", "m")
            put("to_unit", "cm")
        })
        val wrongCase = convert("1", "mb", "B")
        val ambiguousGallon = convert("1", "gallon", "L")
        val crossDimension = convert("1", "kg", "m")
        val oversizedExponent = convert("1e1001", "m", "cm")
        val extra = tools.execute(
            CanonicalToolCall(
                "id",
                CONVERT_UNITS_TOOL_NAME,
                "{\"value\":1,\"from_unit\":\"m\",\"to_unit\":\"cm\",\"extra\":1}",
            ),
        )

        listOf(stringNumber, wrongCase, ambiguousGallon, crossDimension, oversizedExponent, extra)
            .forEach { assertFalse(it.ok) }
        assertTrue(errorValue(stringNumber).contains("JSON number"))
        assertTrue(errorValue(wrongCase).contains("Unknown unit"))
        assertTrue(errorValue(ambiguousGallon).contains("Unknown unit"))
        assertTrue(errorValue(crossDimension).contains("different unit dimensions"))
        assertTrue(errorValue(oversizedExponent).contains("exponent is too large"))
        assertEquals("Invalid tool arguments", errorValue(extra))
    }

    @Test
    fun conversionUsesStringResultsAndRoundTripsWithinDecimal128Precision() = runTest {
        val forward = convert("123.456789", "mi", "km")
        assertTrue(forward.ok)
        val forwardPayload = payload(forward)
        assertTrue(forwardPayload.getValue("input").jsonPrimitive.isString)
        assertTrue(forwardPayload.getValue("result").jsonPrimitive.isString)
        assertEquals("mi", forwardPayload.getValue("from_unit").jsonPrimitive.content)
        assertEquals("km", forwardPayload.getValue("to_unit").jsonPrimitive.content)

        val reverse = convert(forwardPayload.getValue("result").jsonPrimitive.content, "km", "mi")
        assertTrue(reverse.ok)
        assertClose("123.456789", payload(reverse).getValue("result").jsonPrimitive.content, "1E-28")
    }

    private suspend fun assertCalculation(expression: String, expected: String) {
        val result = calculate(expression)
        assertTrue("Calculation failed: ${if (result.ok) result.content else errorValue(result)}", result.ok)
        assertEquals(expected, payload(result).getValue("result").jsonPrimitive.content)
    }

    private suspend fun calculationValue(expression: String): String {
        val result = calculate(expression)
        assertTrue("Calculation failed: ${if (result.ok) result.content else errorValue(result)}", result.ok)
        return payload(result).getValue("result").jsonPrimitive.content
    }

    private suspend fun assertConversion(value: String, from: String, to: String, expected: String) {
        val result = convert(value, from, to)
        assertTrue("Conversion failed: ${if (result.ok) result.content else errorValue(result)}", result.ok)
        assertEquals(expected, payload(result).getValue("result").jsonPrimitive.content)
    }

    private suspend fun conversionValue(value: String, from: String, to: String): String {
        val result = convert(value, from, to)
        assertTrue("Conversion failed: ${if (result.ok) result.content else errorValue(result)}", result.ok)
        return payload(result).getValue("result").jsonPrimitive.content
    }

    private suspend fun calculate(expression: String): ToolExecutionResult = execute(
        CALCULATE_TOOL_NAME,
        buildJsonObject { put("expression", expression) },
    )

    private suspend fun convert(value: String, from: String, to: String): ToolExecutionResult = tools.execute(
        CanonicalToolCall(
            id = "id",
            name = CONVERT_UNITS_TOOL_NAME,
            arguments = "{\"value\":$value,\"from_unit\":\"$from\",\"to_unit\":\"$to\"}",
        ),
    )

    private suspend fun execute(name: String, arguments: JsonObject): ToolExecutionResult = tools.execute(
        CanonicalToolCall("id", name, json.encodeToString(arguments)),
    )

    private fun payload(result: ToolExecutionResult): JsonObject = json.parseToJsonElement(result.content).jsonObject

    private fun errorValue(result: ToolExecutionResult): String =
        payload(result).getValue("error").jsonPrimitive.content

    private fun assertClose(expected: String, actual: String, tolerance: String) {
        val difference = BigDecimal(expected).subtract(BigDecimal(actual)).abs()
        assertTrue("Expected $actual to be within $tolerance of $expected (difference $difference)", difference <= BigDecimal(tolerance))
    }

    private companion object {
        val EXPECTED_UNITS = listOf(
            "mm", "cm", "m", "km", "in", "ft", "yd", "mi", "nmi",
            "mg", "g", "kg", "t", "oz", "lb",
            "C", "F", "K",
            "mm2", "cm2", "m2", "km2", "in2", "ft2", "yd2", "ha", "acre",
            "mL", "L", "m3", "tsp_us", "tbsp_us", "fl_oz_us", "cup_us", "pt_us", "qt_us", "gal_us",
            "fl_oz_imp", "pt_imp", "qt_imp", "gal_imp",
            "m/s", "km/h", "mph", "kn",
            "ms", "s", "min", "h", "d", "wk",
            "Pa", "kPa", "MPa", "bar", "atm", "psi",
            "J", "kJ", "Wh", "kWh", "cal", "kcal",
            "W", "kW", "hp",
            "rad", "deg",
            "bit", "B", "kbit", "Mbit", "Gbit", "KB", "MB", "GB", "TB", "KiB", "MiB", "GiB", "TiB",
        )
    }
}
