package xyz.mek030399.tokenflow.data

import com.ezylang.evalex.EvaluationException
import com.ezylang.evalex.Expression
import com.ezylang.evalex.config.ExpressionConfiguration
import com.ezylang.evalex.data.EvaluationValue
import com.ezylang.evalex.functions.AbstractFunction
import com.ezylang.evalex.functions.FunctionIfc
import com.ezylang.evalex.functions.FunctionParameter
import com.ezylang.evalex.operators.AbstractOperator
import com.ezylang.evalex.operators.InfixOperator
import com.ezylang.evalex.operators.OperatorIfc
import com.ezylang.evalex.parser.Token
import java.math.BigDecimal
import java.math.MathContext
import java.util.AbstractMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class OfflineCalculationTools(
    private val json: Json = DirectApiTransport.defaultJson,
) {
    private val expressionConfiguration = ExpressionConfiguration.builder()
        .mathContext(CALCULATION_CONTEXT)
        .arraysAllowed(false)
        .structuresAllowed(false)
        .maxRecursionDepth(MAX_EXPRESSION_TOKENS + 8)
        .build()
        .withAdditionalFunctions(
            functionEntry("PERCENT", PercentFunction()),
            functionEntry("MOD", ModFunction()),
            functionEntry("ROUND", BoundedRoundFunction()),
        )
        .withAdditionalOperators(operatorEntry("^", BoundedPowerOperator()))

    fun definitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = CALCULATE_TOOL_NAME,
            description = CALCULATE_TOOL_DESCRIPTION,
            parameters = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("expression") {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", MAX_EXPRESSION_CHARS)
                        put(
                            "description",
                            "Required 1-256 character ASCII arithmetic expression to evaluate; no default. Trigonometric functions use radians; use RAD(degrees) and DEG(radians) for explicit conversion.",
                        )
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("expression")) })
            },
        ),
        ToolDefinition(
            name = CONVERT_UNITS_TOOL_NAME,
            description = CONVERT_UNITS_TOOL_DESCRIPTION,
            parameters = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("value") {
                        put("type", "number")
                        put("description", "Required JSON number to convert; no default.")
                    }
                    putJsonObject("from_unit") {
                        put("type", "string")
                        put(
                            "description",
                            "Required case-sensitive source unit identifier; no default. Use exactly one of the listed values.",
                        )
                        put("enum", buildJsonArray { UNIT_NAMES.forEach { add(JsonPrimitive(it)) } })
                    }
                    putJsonObject("to_unit") {
                        put("type", "string")
                        put(
                            "description",
                            "Required case-sensitive target unit identifier; no default. Use a listed value with the same dimension as from_unit.",
                        )
                        put("enum", buildJsonArray { UNIT_NAMES.forEach { add(JsonPrimitive(it)) } })
                    }
                }
                put("required", buildJsonArray {
                    add(JsonPrimitive("value"))
                    add(JsonPrimitive("from_unit"))
                    add(JsonPrimitive("to_unit"))
                })
            },
        ),
    )

    suspend fun execute(call: CanonicalToolCall): ToolExecutionResult = withContext(Dispatchers.Default) {
        val arguments = runCatching { json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
            ?: return@withContext failure("Invalid tool arguments")
        try {
            when (call.name) {
                CALCULATE_TOOL_NAME -> executeCalculation(arguments)
                CONVERT_UNITS_TOOL_NAME -> executeConversion(arguments)
                else -> failure("Unknown tool: ${call.name}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure(failure.message ?: "Offline tool failed")
        }
    }

    private fun executeCalculation(arguments: JsonObject): ToolExecutionResult {
        requireKeys(arguments, setOf("expression"))
        val expressionValue = arguments["expression"] as? JsonPrimitive
        if (expressionValue == null || !expressionValue.isString) {
            throw OfflineToolException("expression must be a string")
        }
        val normalizedExpression = normalizeExpression(expressionValue.content)
        val evaluated = Expression(normalizedExpression, expressionConfiguration).evaluate()
        if (!evaluated.isNumberValue) throw OfflineToolException("Expression must produce a numeric result")
        val result = formatNumber(evaluated.numberValue)
        if (result.length > MAX_RESULT_CHARS) throw OfflineToolException("Calculation result is too large")
        return ToolExecutionResult(
            content = json.encodeToString(buildJsonObject { put("result", result) }),
            ok = true,
        )
    }

    private fun executeConversion(arguments: JsonObject): ToolExecutionResult {
        requireKeys(arguments, setOf("value", "from_unit", "to_unit"))
        val rawValue = arguments["value"] as? JsonPrimitive
        if (rawValue == null || rawValue.isString) throw OfflineToolException("value must be a JSON number")
        val value = parseBoundedDecimal(rawValue.content, "value")
        val fromName = stringArgument(arguments, "from_unit")
        val toName = stringArgument(arguments, "to_unit")
        val from = UNIT_DEFINITIONS[fromName] ?: throw OfflineToolException("Unknown unit: $fromName")
        val to = UNIT_DEFINITIONS[toName] ?: throw OfflineToolException("Unknown unit: $toName")
        if (from.dimension != to.dimension) {
            throw OfflineToolException("Cannot convert between different unit dimensions")
        }

        val baseValue = from.toBase(value)
        if (from.dimension == UnitDimension.TEMPERATURE && baseValue.signum() < 0) {
            throw OfflineToolException("Temperature cannot be below absolute zero")
        }
        val converted = if (fromName == toName) value else to.fromBase(baseValue)
        val inputText = formatNumber(value)
        val resultText = formatNumber(converted)
        if (resultText.length > MAX_RESULT_CHARS) throw OfflineToolException("Conversion result is too large")
        return ToolExecutionResult(
            content = json.encodeToString(buildJsonObject {
                put("input", inputText)
                put("from_unit", fromName)
                put("result", resultText)
                put("to_unit", toName)
            }),
            ok = true,
        )
    }

    private fun requireKeys(arguments: JsonObject, expected: Set<String>) {
        if (arguments.keys != expected) throw OfflineToolException("Invalid tool arguments")
    }

    private fun stringArgument(arguments: JsonObject, name: String): String {
        val value = arguments[name] as? JsonPrimitive
        if (value == null || !value.isString) throw OfflineToolException("$name must be a string")
        return value.content
    }

    private fun failure(message: String): ToolExecutionResult {
        val safeMessage = message
            .replace(CONTROL_WHITESPACE, " ")
            .trim()
            .ifBlank { "Offline tool failed" }
            .take(MAX_ERROR_CHARS)
        return ToolExecutionResult(
            content = json.encodeToString(buildJsonObject { put("error", safeMessage) }),
            ok = false,
        )
    }
}

private fun normalizeExpression(expression: String): String {
    if (expression.isBlank()) throw OfflineToolException("expression must not be blank")
    if (expression.length > MAX_EXPRESSION_CHARS) throw OfflineToolException("expression is too long")
    if (expression.any { it.code > 0x7f }) throw OfflineToolException("expression must contain ASCII characters only")

    val tokens = mutableListOf<ExpressionToken>()
    var index = 0
    var parenthesisDepth = 0
    while (index < expression.length) {
        val current = expression[index]
        when {
            current.isWhitespace() -> index++
            current.isDigit() || (current == '.' && expression.getOrNull(index + 1)?.isDigit() == true) -> {
                val match = NUMBER_LITERAL.matchAt(expression, index)
                    ?: throw OfflineToolException("Invalid number")
                val value = match.value
                if (value.length > MAX_NUMBER_CHARS) throw OfflineToolException("Number literal is too long")
                validateExponent(value)
                runCatching { BigDecimal(value, CALCULATION_CONTEXT) }
                    .getOrElse { throw OfflineToolException("Invalid number") }
                tokens += ExpressionToken(ExpressionTokenType.NUMBER, value)
                index += value.length
            }
            current.isAsciiLetter() -> {
                val start = index
                index++
                while (index < expression.length &&
                    (expression[index].isAsciiLetter() || expression[index].isDigit() || expression[index] == '_')
                ) {
                    index++
                }
                tokens += ExpressionToken(ExpressionTokenType.IDENTIFIER, expression.substring(start, index))
            }
            current in ALLOWED_EXPRESSION_SYMBOLS -> {
                if (current == '(') {
                    parenthesisDepth++
                    if (parenthesisDepth > MAX_PARENTHESIS_DEPTH) {
                        throw OfflineToolException("expression parentheses are nested too deeply")
                    }
                } else if (current == ')') {
                    parenthesisDepth--
                    if (parenthesisDepth < 0) throw OfflineToolException("expression has unbalanced parentheses")
                }
                tokens += ExpressionToken(ExpressionTokenType.SYMBOL, current.toString())
                index++
            }
            current == '%' -> throw OfflineToolException("Use MOD(a,b) for modulo or PERCENT(x) for percentages")
            else -> throw OfflineToolException("expression contains an unsupported character")
        }
        if (tokens.size > MAX_EXPRESSION_TOKENS) throw OfflineToolException("expression is too complex")
    }
    if (tokens.isEmpty()) throw OfflineToolException("expression must not be blank")
    if (parenthesisDepth != 0) throw OfflineToolException("expression has unbalanced parentheses")

    tokens.forEachIndexed { tokenIndex, token ->
        if (token.type != ExpressionTokenType.IDENTIFIER) return@forEachIndexed
        val identifier = token.value.uppercase()
        val followedByParenthesis = tokens.getOrNull(tokenIndex + 1)?.value == "("
        when {
            identifier in ALLOWED_CONSTANTS && !followedByParenthesis -> Unit
            identifier in ALLOWED_FUNCTIONS && followedByParenthesis -> Unit
            identifier in ALLOWED_FUNCTIONS -> throw OfflineToolException("Function $identifier must be followed by parentheses")
            else -> throw OfflineToolException("Unknown identifier: $identifier")
        }
    }

    return tokens.joinToString(" ") { token ->
        if (token.type == ExpressionTokenType.IDENTIFIER) {
            RADIAN_FUNCTION_NAMES[token.value.uppercase()] ?: token.value.uppercase()
        } else {
            token.value
        }
    }
}

private fun validateExponent(number: String) {
    val marker = number.indexOfFirst { it == 'e' || it == 'E' }
    if (marker < 0) return
    val exponent = number.substring(marker + 1).toIntOrNull()
    if (exponent == null || exponent !in -MAX_LITERAL_EXPONENT..MAX_LITERAL_EXPONENT) {
        throw OfflineToolException("Number exponent is too large")
    }
}

private fun parseBoundedDecimal(raw: String, fieldName: String): BigDecimal {
    if (raw.length > MAX_NUMBER_CHARS || !JSON_NUMBER.matches(raw)) {
        throw OfflineToolException("$fieldName must be a valid bounded number")
    }
    validateExponent(raw)
    return runCatching { BigDecimal(raw, CALCULATION_CONTEXT) }
        .getOrElse { throw OfflineToolException("$fieldName must be a valid bounded number") }
}

private fun formatNumber(value: BigDecimal): String {
    if (value.signum() == 0) return "0"
    val normalized = value.round(CALCULATION_CONTEXT).stripTrailingZeros()
    val adjustedExponent = normalized.precision() - normalized.scale() - 1
    return if (adjustedExponent in PLAIN_MIN_EXPONENT..PLAIN_MAX_EXPONENT) {
        normalized.toPlainString()
    } else {
        normalized.toString().replace('e', 'E')
    }
}

@FunctionParameter(name = "value")
private class PercentFunction : AbstractFunction() {
    override fun evaluate(
        expression: Expression,
        functionToken: Token,
        vararg parameterValues: EvaluationValue,
    ): EvaluationValue = expression.convertValue(
        parameterValues[0].numberValue.divide(ONE_HUNDRED, expression.configuration.mathContext),
    )
}

@FunctionParameter(name = "dividend")
@FunctionParameter(name = "divisor")
private class ModFunction : AbstractFunction() {
    override fun evaluate(
        expression: Expression,
        functionToken: Token,
        vararg parameterValues: EvaluationValue,
    ): EvaluationValue {
        val divisor = parameterValues[1].numberValue
        if (divisor.signum() == 0) throw EvaluationException(functionToken, "MOD divisor must not be zero")
        return expression.convertValue(
            parameterValues[0].numberValue.remainder(divisor, expression.configuration.mathContext),
        )
    }
}

@FunctionParameter(name = "value")
@FunctionParameter(name = "scale")
private class BoundedRoundFunction : AbstractFunction() {
    override fun evaluate(
        expression: Expression,
        functionToken: Token,
        vararg parameterValues: EvaluationValue,
    ): EvaluationValue {
        val rawScale = parameterValues[1].numberValue.stripTrailingZeros()
        val scale = runCatching {
            if (rawScale.scale() > 0) throw ArithmeticException()
            rawScale.intValueExact()
        }.getOrElse { throw EvaluationException(functionToken, "ROUND scale must be an integer") }
        if (scale !in -MAX_ROUND_SCALE..MAX_ROUND_SCALE) {
            throw EvaluationException(functionToken, "ROUND scale must be between -$MAX_ROUND_SCALE and $MAX_ROUND_SCALE")
        }
        return expression.convertValue(
            parameterValues[0].numberValue.setScale(scale, expression.configuration.mathContext.roundingMode),
        )
    }
}

@InfixOperator(precedence = OperatorIfc.OPERATOR_PRECEDENCE_POWER, leftAssociative = false)
private class BoundedPowerOperator : AbstractOperator() {
    override fun evaluate(
        expression: Expression,
        operatorToken: Token,
        vararg operands: EvaluationValue,
    ): EvaluationValue {
        if (operands.size != 2 || operands.any { !it.isNumberValue }) {
            throw EvaluationException.ofUnsupportedDataTypeInOperation(operatorToken)
        }
        val base = operands[0].numberValue
        val exponent = operands[1].numberValue
        if (exponent.abs() > MAX_POWER_EXPONENT_DECIMAL) {
            throw EvaluationException(operatorToken, "Power exponent must be between -$MAX_POWER_EXPONENT and $MAX_POWER_EXPONENT")
        }
        val mathContext = expression.configuration.mathContext
        val normalizedExponent = exponent.stripTrailingZeros()
        val result = if (normalizedExponent.scale() <= 0) {
            val magnitude = normalizedExponent.abs().intValueExact()
            val powered = base.pow(magnitude, mathContext)
            if (normalizedExponent.signum() < 0) {
                if (powered.signum() == 0) throw EvaluationException(operatorToken, "Division by zero")
                BigDecimal.ONE.divide(powered, mathContext)
            } else {
                powered
            }
        } else {
            val approximate = Math.pow(base.toDouble(), exponent.toDouble())
            if (!approximate.isFinite()) throw EvaluationException(operatorToken, "Power result is not finite")
            BigDecimal.valueOf(approximate).round(mathContext)
        }
        return expression.convertValue(result)
    }
}

private fun functionEntry(name: String, function: FunctionIfc): Map.Entry<String, FunctionIfc> =
    AbstractMap.SimpleImmutableEntry(name, function)

private fun operatorEntry(name: String, operator: OperatorIfc): Map.Entry<String, OperatorIfc> =
    AbstractMap.SimpleImmutableEntry(name, operator)

private enum class UnitDimension {
    LENGTH,
    MASS,
    TEMPERATURE,
    AREA,
    VOLUME,
    SPEED,
    TIME,
    PRESSURE,
    ENERGY,
    POWER,
    ANGLE,
    DATA,
}

private data class UnitDefinition(
    val dimension: UnitDimension,
    val numerator: BigDecimal,
    val denominator: BigDecimal = BigDecimal.ONE,
    val inputShift: BigDecimal = BigDecimal.ZERO,
) {
    fun toBase(value: BigDecimal): BigDecimal = value
        .add(inputShift, CALCULATION_CONTEXT)
        .multiply(numerator, CALCULATION_CONTEXT)
        .divide(denominator, CALCULATION_CONTEXT)

    fun fromBase(value: BigDecimal): BigDecimal = value
        .multiply(denominator, CALCULATION_CONTEXT)
        .divide(numerator, CALCULATION_CONTEXT)
        .subtract(inputShift, CALCULATION_CONTEXT)
}

private fun linear(dimension: UnitDimension, factor: String) = UnitDefinition(dimension, BigDecimal(factor))

private fun rational(dimension: UnitDimension, numerator: String, denominator: String) = UnitDefinition(
    dimension = dimension,
    numerator = BigDecimal(numerator),
    denominator = BigDecimal(denominator),
)

private val PI_DECIMAL = BigDecimal("3.141592653589793238462643383279503")

private val UNIT_DEFINITIONS: Map<String, UnitDefinition> = linkedMapOf(
    // Length, base unit: metre.
    "mm" to linear(UnitDimension.LENGTH, "0.001"),
    "cm" to linear(UnitDimension.LENGTH, "0.01"),
    "m" to linear(UnitDimension.LENGTH, "1"),
    "km" to linear(UnitDimension.LENGTH, "1000"),
    "in" to linear(UnitDimension.LENGTH, "0.0254"),
    "ft" to linear(UnitDimension.LENGTH, "0.3048"),
    "yd" to linear(UnitDimension.LENGTH, "0.9144"),
    "mi" to linear(UnitDimension.LENGTH, "1609.344"),
    "nmi" to linear(UnitDimension.LENGTH, "1852"),

    // Mass, base unit: kilogram.
    "mg" to linear(UnitDimension.MASS, "0.000001"),
    "g" to linear(UnitDimension.MASS, "0.001"),
    "kg" to linear(UnitDimension.MASS, "1"),
    "t" to linear(UnitDimension.MASS, "1000"),
    "oz" to linear(UnitDimension.MASS, "0.028349523125"),
    "lb" to linear(UnitDimension.MASS, "0.45359237"),

    // Temperature, base unit: kelvin. The input shift is applied before the ratio.
    "C" to UnitDefinition(UnitDimension.TEMPERATURE, BigDecimal.ONE, inputShift = BigDecimal("273.15")),
    "F" to UnitDefinition(
        UnitDimension.TEMPERATURE,
        numerator = BigDecimal("5"),
        denominator = BigDecimal("9"),
        inputShift = BigDecimal("459.67"),
    ),
    "K" to linear(UnitDimension.TEMPERATURE, "1"),

    // Area, base unit: square metre.
    "mm2" to linear(UnitDimension.AREA, "0.000001"),
    "cm2" to linear(UnitDimension.AREA, "0.0001"),
    "m2" to linear(UnitDimension.AREA, "1"),
    "km2" to linear(UnitDimension.AREA, "1000000"),
    "in2" to linear(UnitDimension.AREA, "0.00064516"),
    "ft2" to linear(UnitDimension.AREA, "0.09290304"),
    "yd2" to linear(UnitDimension.AREA, "0.83612736"),
    "ha" to linear(UnitDimension.AREA, "10000"),
    "acre" to linear(UnitDimension.AREA, "4046.8564224"),

    // Volume, base unit: litre.
    "mL" to linear(UnitDimension.VOLUME, "0.001"),
    "L" to linear(UnitDimension.VOLUME, "1"),
    "m3" to linear(UnitDimension.VOLUME, "1000"),
    "tsp_us" to linear(UnitDimension.VOLUME, "0.00492892159375"),
    "tbsp_us" to linear(UnitDimension.VOLUME, "0.01478676478125"),
    "fl_oz_us" to linear(UnitDimension.VOLUME, "0.0295735295625"),
    "cup_us" to linear(UnitDimension.VOLUME, "0.2365882365"),
    "pt_us" to linear(UnitDimension.VOLUME, "0.473176473"),
    "qt_us" to linear(UnitDimension.VOLUME, "0.946352946"),
    "gal_us" to linear(UnitDimension.VOLUME, "3.785411784"),
    "fl_oz_imp" to linear(UnitDimension.VOLUME, "0.0284130625"),
    "pt_imp" to linear(UnitDimension.VOLUME, "0.56826125"),
    "qt_imp" to linear(UnitDimension.VOLUME, "1.1365225"),
    "gal_imp" to linear(UnitDimension.VOLUME, "4.54609"),

    // Speed, base unit: metre per second.
    "m/s" to linear(UnitDimension.SPEED, "1"),
    "km/h" to rational(UnitDimension.SPEED, "5", "18"),
    "mph" to linear(UnitDimension.SPEED, "0.44704"),
    "kn" to rational(UnitDimension.SPEED, "463", "900"),

    // Time, base unit: second.
    "ms" to linear(UnitDimension.TIME, "0.001"),
    "s" to linear(UnitDimension.TIME, "1"),
    "min" to linear(UnitDimension.TIME, "60"),
    "h" to linear(UnitDimension.TIME, "3600"),
    "d" to linear(UnitDimension.TIME, "86400"),
    "wk" to linear(UnitDimension.TIME, "604800"),

    // Pressure, base unit: pascal.
    "Pa" to linear(UnitDimension.PRESSURE, "1"),
    "kPa" to linear(UnitDimension.PRESSURE, "1000"),
    "MPa" to linear(UnitDimension.PRESSURE, "1000000"),
    "bar" to linear(UnitDimension.PRESSURE, "100000"),
    "atm" to linear(UnitDimension.PRESSURE, "101325"),
    "psi" to linear(UnitDimension.PRESSURE, "6894.757293168361336722673445346891"),

    // Energy, base unit: joule. cal and kcal are thermochemical calories.
    "J" to linear(UnitDimension.ENERGY, "1"),
    "kJ" to linear(UnitDimension.ENERGY, "1000"),
    "Wh" to linear(UnitDimension.ENERGY, "3600"),
    "kWh" to linear(UnitDimension.ENERGY, "3600000"),
    "cal" to linear(UnitDimension.ENERGY, "4.184"),
    "kcal" to linear(UnitDimension.ENERGY, "4184"),

    // Power, base unit: watt. hp is mechanical horsepower.
    "W" to linear(UnitDimension.POWER, "1"),
    "kW" to linear(UnitDimension.POWER, "1000"),
    "hp" to linear(UnitDimension.POWER, "745.69987158227022"),

    // Angle, base unit: radian.
    "rad" to linear(UnitDimension.ANGLE, "1"),
    "deg" to UnitDefinition(UnitDimension.ANGLE, PI_DECIMAL, BigDecimal("180")),

    // Data size, base unit: bit. Decimal and IEC prefixes are intentionally distinct.
    "bit" to linear(UnitDimension.DATA, "1"),
    "B" to linear(UnitDimension.DATA, "8"),
    "kbit" to linear(UnitDimension.DATA, "1000"),
    "Mbit" to linear(UnitDimension.DATA, "1000000"),
    "Gbit" to linear(UnitDimension.DATA, "1000000000"),
    "KB" to linear(UnitDimension.DATA, "8000"),
    "MB" to linear(UnitDimension.DATA, "8000000"),
    "GB" to linear(UnitDimension.DATA, "8000000000"),
    "TB" to linear(UnitDimension.DATA, "8000000000000"),
    "KiB" to linear(UnitDimension.DATA, "8192"),
    "MiB" to linear(UnitDimension.DATA, "8388608"),
    "GiB" to linear(UnitDimension.DATA, "8589934592"),
    "TiB" to linear(UnitDimension.DATA, "8796093022208"),
)

private val UNIT_NAMES = UNIT_DEFINITIONS.keys.toList()

private enum class ExpressionTokenType { NUMBER, IDENTIFIER, SYMBOL }

private data class ExpressionToken(val type: ExpressionTokenType, val value: String)

private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

private class OfflineToolException(message: String) : IllegalArgumentException(message)

internal const val CALCULATE_TOOL_NAME = "calculate"
internal const val CONVERT_UNITS_TOOL_NAME = "convert_units"

internal const val CALCULATE_TOOL_DESCRIPTION =
    "Evaluate a deterministic numeric expression entirely on this device; no request is sent to a network service. The expression and returned JSON remain in the ongoing model conversation. Returns the decimal result as a string in result. Supports +, -, *, /, ^, parentheses, PI, E, and ABS, SQRT, LOG, LOG10, MIN, MAX, SUM, AVERAGE, ROUND, FLOOR, CEILING, SIN, COS, TAN, ASIN, ACOS, ATAN, ATAN2, SINH, COSH, TANH, RAD, DEG, PERCENT, and MOD. Trigonometric inputs and inverse results use radians. Use PERCENT(13) for 13 percent and MOD(a,b) for modulo; the % operator is not accepted. ROUND scale must be an integer from -100 to 100."

internal const val CONVERT_UNITS_TOOL_DESCRIPTION =
    "Convert a numeric value entirely on this device between canonical units of the same dimension; no request is sent to a network service. The arguments and returned JSON remain in the ongoing model conversation. Returns input, from_unit, result, and to_unit as strings. Unit identifiers are case-sensitive. B means byte and bit means bit; US and imperial volume units must be selected explicitly. Currency conversion is not supported."

private val CALCULATION_CONTEXT: MathContext = MathContext.DECIMAL128
private val ONE_HUNDRED = BigDecimal("100")
private const val MAX_EXPRESSION_CHARS = 256
private const val MAX_EXPRESSION_TOKENS = 128
private const val MAX_PARENTHESIS_DEPTH = 16
private const val MAX_NUMBER_CHARS = 64
private const val MAX_LITERAL_EXPONENT = 1_000
private const val MAX_POWER_EXPONENT = 1_000
private val MAX_POWER_EXPONENT_DECIMAL = BigDecimal(MAX_POWER_EXPONENT)
private const val MAX_ROUND_SCALE = 100
private const val MAX_RESULT_CHARS = 512
private const val MAX_ERROR_CHARS = 200
private const val PLAIN_MIN_EXPONENT = -6
private const val PLAIN_MAX_EXPONENT = 20

private val NUMBER_LITERAL = Regex("""(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?""")
private val JSON_NUMBER = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")
private val CONTROL_WHITESPACE = Regex("[\\r\\n\\t]+")
private val ALLOWED_EXPRESSION_SYMBOLS = setOf('+', '-', '*', '/', '^', '(', ')', ',')
private val ALLOWED_CONSTANTS = setOf("PI", "E")
private val ALLOWED_FUNCTIONS = setOf(
    "ABS",
    "SQRT",
    "LOG",
    "LOG10",
    "MIN",
    "MAX",
    "SUM",
    "AVERAGE",
    "ROUND",
    "FLOOR",
    "CEILING",
    "SIN",
    "COS",
    "TAN",
    "ASIN",
    "ACOS",
    "ATAN",
    "ATAN2",
    "SINH",
    "COSH",
    "TANH",
    "RAD",
    "DEG",
    "PERCENT",
    "MOD",
)
private val RADIAN_FUNCTION_NAMES = mapOf(
    "SIN" to "SINR",
    "COS" to "COSR",
    "TAN" to "TANR",
    "ASIN" to "ASINR",
    "ACOS" to "ACOSR",
    "ATAN" to "ATANR",
    "ATAN2" to "ATAN2R",
)
