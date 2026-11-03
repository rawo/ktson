package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * "100e2147483647" is a BigDecimal with scale -2147483647; stripping its trailing zeros needs a scale below
 * Int.MIN_VALUE, so stripTrailingZeros() throws. type and multipleOf must decide such numbers without stripping (#86).
 */
class ScaleBoundaryTest :
    DescribeSpec({
        fun isValid(instance: String, schema: String): Boolean = JsonValidator().validate(Json.parseToJsonElement(instance), JsonSchema.fromString(schema)).isValid

        val edge = listOf("100e2147483647", "-100e2147483647", "1000e2147483647", "2500e2147483647")

        it("classifies numbers whose stripped scale would overflow as integers") {
            edge.forEach { number ->
                isValid(number, """{"type":"integer"}""") shouldBe true
                isValid(number, """{"type":"number"}""") shouldBe true
                isValid(number, """{"type":"string"}""") shouldBe false
            }
        }

        it("decides multipleOf for such instances") {
            edge.forEach { number -> isValid(number, """{"multipleOf":1}""") shouldBe true }
            isValid("100e2147483647", """{"multipleOf":5}""") shouldBe true
            isValid("100e2147483647", """{"multipleOf":0.25}""") shouldBe true
            isValid("100e2147483647", """{"multipleOf":3}""") shouldBe false
            isValid("300e2147483647", """{"multipleOf":3}""") shouldBe true
            isValid("2500e2147483647", """{"multipleOf":1e2147483647}""") shouldBe true
            isValid("100e2147483646", """{"multipleOf":100e2147483647}""") shouldBe false
        }

        it("decides multipleOf for such divisors") {
            isValid("1", """{"multipleOf":100e2147483647}""") shouldBe false
            isValid("0", """{"multipleOf":100e2147483647}""") shouldBe true
            isValid("100e2147483647", """{"multipleOf":100e2147483647}""") shouldBe true
            isValid("1e2147483647", """{"multipleOf":100e2147483647}""") shouldBe false
            isValid("7000e2147483647", """{"multipleOf":700e2147483647}""") shouldBe true
        }

        it("decides tiny fractions against divisors with many fives") {
            isValid("1e-2147483647", """{"multipleOf":5}""") shouldBe false
            isValid("1e-2147483647", """{"multipleOf":5e-2147483647}""") shouldBe false
            isValid("5e-2147483647", """{"multipleOf":5e-2147483647}""") shouldBe true
            isValid("25", """{"multipleOf":5e-2147483647}""") shouldBe true
        }
    })
