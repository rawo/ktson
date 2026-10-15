package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class NumericPrecisionTest :
    DescribeSpec({
    val validator = JsonValidator()
    val cases = listOf(
        NumericCase("minimum above 2^53", """{"minimum":9007199254740993}""", "9007199254740992", false),
        NumericCase("maximum above 2^53", """{"maximum":9007199254740992}""", "9007199254740993", false),
        NumericCase("minimum beyond Long", """{"minimum":9223372036854775809}""", "9223372036854775808", false),
        NumericCase("negative maximum beyond Long", """{"maximum":-9223372036854775809}""", "-9223372036854775808", false),
        NumericCase("inclusive minimum equality", """{"minimum":9007199254740993}""", "9007199254740993.0", true),
        NumericCase("inclusive maximum equality", """{"maximum":9007199254740993.0}""", "9007199254740993", true),
        NumericCase("exclusive minimum equality", """{"exclusiveMinimum":9007199254740993}""", "9007199254740993.0", false),
        NumericCase("exclusive maximum equality", """{"exclusiveMaximum":9007199254740993.0}""", "9007199254740993", false),
        NumericCase("exclusive minimum distinct integers", """{"exclusiveMinimum":9007199254740992}""", "9007199254740993", true),
        NumericCase("exclusive maximum distinct integers", """{"exclusiveMaximum":9007199254740993}""", "9007199254740992", true),
        NumericCase("fraction below minimum", """{"minimum":1.0000000000000000002}""", "1.0000000000000000001", false),
        NumericCase("fraction above maximum", """{"maximum":1.0000000000000000001}""", "1.0000000000000000002", false),
        NumericCase("huge exponent integer", """{"type":"integer"}""", "1e400", true),
        NumericCase("tiny exponent fraction", """{"type":"integer"}""", "1e-400", false),
        NumericCase("fraction close to integer", """{"type":"integer"}""", "1.0000000000000000001", false),
        NumericCase("decimal integer", """{"type":"integer"}""", "1.000", true),
        NumericCase("exponent integer", """{"type":"integer"}""", "1.20e2", true),
        NumericCase("negative zero integer", """{"type":"integer"}""", "-0.0", true),
        NumericCase("const distinct large integers", """{"const":9007199254740993}""", "9007199254740992", false),
        NumericCase("enum distinct large integers", """{"enum":[9007199254740993]}""", "9007199254740992", false),
        NumericCase("const decimal equality", """{"const":1}""", "1.0", true),
        NumericCase("enum exponent equality", """{"enum":[9007199254740993]}""", "9007199254740993e0", true),
        NumericCase("const huge exponent equality", """{"const":1e400}""", "10e399", true),
        NumericCase("const distinct huge exponents", """{"const":1e400}""", "2e400", false),
        NumericCase("numeric string is distinct", """{"const":1}""", "\"1\"", false),
        NumericCase("enum numeric string is distinct", """{"enum":[1]}""", "\"1\"", false),
        NumericCase("unique distinct large integers", """{"uniqueItems":true}""", "[9007199254740992,9007199254740993]", true),
        NumericCase("unique equivalent decimals", """{"uniqueItems":true}""", "[1,1.0]", false),
        NumericCase("unique string and number", """{"uniqueItems":true}""", "[1,\"1\"]", true),
        NumericCase("nested equivalent numbers", """{"const":{"n":[9007199254740993]}}""", """{"n":[9007199254740993.0]}""", true),
        NumericCase("nested distinct numbers", """{"const":{"n":[9007199254740993]}}""", """{"n":[9007199254740992]}""", false),
        NumericCase("nested duplicate numbers", """{"uniqueItems":true}""", """[{"n":1},{"n":1.0}]""", false),
        NumericCase("exact decimal multiple", """{"multipleOf":0.0001}""", "0.0075", true),
        NumericCase("near decimal multiple", """{"multipleOf":0.1}""", "0.3000000000000000001", false),
        NumericCase("tiny non-multiple", """{"multipleOf":0.1}""", "1e-400", false),
        NumericCase("tiny exact multiple", """{"multipleOf":1e-400}""", "3e-400", true),
        NumericCase("large integer non-multiple", """{"multipleOf":2}""", "9007199254740993", false),
        NumericCase("Long minimum multiple", """{"multipleOf":2}""", "-9223372036854775808", true),
        NumericCase("beyond Long multiple", """{"multipleOf":3}""", "9223372036854775809", true),
        NumericCase("decimal instance integer divisor", """{"multipleOf":2}""", "6.0", true),
        NumericCase("integer instance decimal divisor", """{"multipleOf":0.5}""", "3", true),
        NumericCase("negative decimal multiple", """{"multipleOf":0.1}""", "-0.3", true),
        NumericCase("zero multiple", """{"multipleOf":0.1}""", "0", true),
    )

    SchemaVersion.entries.forEach { version ->
        describe(version.name) {
            cases.forEach { case ->
                it(case.description) {
                    val result = validator.validate(Json.parseToJsonElement(case.instance), JsonSchema.fromString(case.schema, version))
                    result.isValid shouldBe case.valid
                }
            }
            it("validates precise numbers concurrently with shared schema limits") {
                val concurrentValidator = JsonValidator()
                val executor = Executors.newFixedThreadPool(4)
                try {
                    val tasks = List(8) { cases }.flatten().map { case ->
                        Callable {
                            concurrentValidator.validate(case.instance, case.schema, version).isValid shouldBe case.valid
                        }
                    }
                    executor.invokeAll(tasks).forEach { it.get() }
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }
})

private data class NumericCase(val description: String, val schema: String, val instance: String, val valid: Boolean)
