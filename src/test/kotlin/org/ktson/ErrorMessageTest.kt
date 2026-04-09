package org.ktson

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ErrorMessageTest :
    DescribeSpec({
    val validator = JsonValidator()

    describe("const error messages") {
        it("includes actual value in error message") {
            runTest {
                val schema = JsonSchema.fromString("""{"const": 42}""", SchemaVersion.DRAFT_2020_12)
                val result = validator.validate(JsonPrimitive("hello"), schema) as ValidationResult.Invalid
                result.validationErrors.first().message shouldContain "42"
                result.validationErrors.first().message shouldContain "hello"
            }
        }

        it("includes object actual value") {
            runTest {
                val schema = JsonSchema.fromString("""{"const": {"a": 1}}""", SchemaVersion.DRAFT_2020_12)
                val result = validator.validate(JsonPrimitive(99), schema) as ValidationResult.Invalid
                result.validationErrors.first().message shouldContain "but was"
            }
        }
    }

    describe("enum error messages") {
        it("includes actual value and allowed values") {
            runTest {
                val schema = JsonSchema.fromString("""{"enum": ["a", "b", "c"]}""", SchemaVersion.DRAFT_2020_12)
                val result = validator.validate(JsonPrimitive("x"), schema) as ValidationResult.Invalid
                val msg = result.validationErrors.first().message
                msg shouldContain "x"
                msg shouldContain "a"
            }
        }

        it("includes numeric actual value") {
            runTest {
                val schema = JsonSchema.fromString("""{"enum": [1, 2, 3]}""", SchemaVersion.DRAFT_2020_12)
                val result = validator.validate(JsonPrimitive(9), schema) as ValidationResult.Invalid
                result.validationErrors.first().message shouldContain "9"
            }
        }
    }

    describe("allOf error messages") {
        it("wraps branch failures with index") {
            runTest {
                val schema = JsonSchema.fromString(
                    """{"allOf": [{"type": "string"}, {"minLength": 10}]}""",
                    SchemaVersion.DRAFT_2020_12,
                )
                val result = validator.validate(JsonPrimitive(42), schema) as ValidationResult.Invalid
                val allOfError = result.validationErrors.first { it.keyword == "allOf" }
                allOfError.message shouldContain "index 0"
                allOfError.causes shouldHaveSize 1
            }
        }

        it("reports correct branch index when second branch fails") {
            runTest {
                val schema = JsonSchema.fromString(
                    """{"allOf": [{"type": "string"}, {"minLength": 10}]}""",
                    SchemaVersion.DRAFT_2020_12,
                )
                val result = validator.validate(JsonPrimitive("hi"), schema) as ValidationResult.Invalid
                val allOfError = result.validationErrors.first { it.keyword == "allOf" }
                allOfError.message shouldContain "index 1"
                allOfError.causes.first().keyword shouldBe "minLength"
            }
        }

        it("includes nested cause errors") {
            runTest {
                val schema = JsonSchema.fromString(
                    """{"allOf": [{"minimum": 5}]}""",
                    SchemaVersion.DRAFT_2020_12,
                )
                val result = validator.validate(JsonPrimitive(1), schema) as ValidationResult.Invalid
                val allOfError = result.validationErrors.first { it.keyword == "allOf" }
                allOfError.causes shouldHaveSize 1
                allOfError.causes.first().keyword shouldBe "minimum"
            }
        }
    }

    describe("anyOf error messages") {
        it("includes schema count in message") {
            runTest {
                val schema = JsonSchema.fromString(
                    """{"anyOf": [{"type": "string"}, {"type": "boolean"}]}""",
                    SchemaVersion.DRAFT_2020_12,
                )
                val result = validator.validate(JsonPrimitive(42), schema) as ValidationResult.Invalid
                val anyOfError = result.validationErrors.first { it.keyword == "anyOf" }
                anyOfError.message shouldContain "2"
                anyOfError.message shouldContain "anyOf"
            }
        }

        it("includes branch causes with branch index") {
            runTest {
                val schema = JsonSchema.fromString(
                    """{"anyOf": [{"type": "string"}, {"minimum": 100}]}""",
                    SchemaVersion.DRAFT_2020_12,
                )
                val result = validator.validate(JsonPrimitive(42), schema) as ValidationResult.Invalid
                val anyOfError = result.validationErrors.first { it.keyword == "anyOf" }
                anyOfError.causes shouldHaveSize 2
                anyOfError.causes[0].message shouldContain "Branch 0"
                anyOfError.causes[1].message shouldContain "Branch 1"
            }
        }

        it("cause errors contain nested keyword errors") {
            runTest {
                val schema = JsonSchema.fromString(
                    """{"anyOf": [{"type": "string"}, {"minimum": 100}]}""",
                    SchemaVersion.DRAFT_2020_12,
                )
                val result = validator.validate(JsonPrimitive(42), schema) as ValidationResult.Invalid
                val anyOfError = result.validationErrors.first { it.keyword == "anyOf" }
                val branch0Causes = anyOfError.causes[0].causes
                branch0Causes.first().keyword shouldBe "type"
                val branch1Causes = anyOfError.causes[1].causes
                branch1Causes.first().keyword shouldBe "minimum"
            }
        }
    }

    describe("oneOf error messages") {
        it("includes schema count when no branch matches") {
            runTest {
                val schema = JsonSchema.fromString(
                    """{"oneOf": [{"type": "string"}, {"type": "boolean"}]}""",
                    SchemaVersion.DRAFT_2020_12,
                )
                val result = validator.validate(JsonPrimitive(42), schema) as ValidationResult.Invalid
                val oneOfError = result.validationErrors.first { it.keyword == "oneOf" }
                oneOfError.message shouldContain "2"
            }
        }

        it("includes branch causes when no branch matches") {
            runTest {
                val schema = JsonSchema.fromString(
                    """{"oneOf": [{"type": "string"}, {"minimum": 100}]}""",
                    SchemaVersion.DRAFT_2020_12,
                )
                val result = validator.validate(JsonPrimitive(42), schema) as ValidationResult.Invalid
                val oneOfError = result.validationErrors.first { it.keyword == "oneOf" }
                oneOfError.causes shouldHaveSize 2
            }
        }

        it("shows match count when multiple branches match") {
            runTest {
                val schema = JsonSchema.fromString(
                    """{"oneOf": [{"type": "integer"}, {"minimum": 1}]}""",
                    SchemaVersion.DRAFT_2020_12,
                )
                val result = validator.validate(JsonPrimitive(5), schema) as ValidationResult.Invalid
                val oneOfError = result.validationErrors.first { it.keyword == "oneOf" }
                oneOfError.message shouldContain "2"
                oneOfError.message shouldContain "expected exactly 1"
            }
        }
    }

    describe("ValidationError toString") {
        it("shows causes indented") {
            runTest {
                val cause = ValidationError("a.b", "inner error", "type")
                val parent = ValidationError("a", "outer error", "allOf", causes = listOf(cause))
                val str = parent.toString()
                str shouldContain "outer error"
                str shouldContain "inner error"
                str shouldContain "  " // indentation for cause
            }
        }
    }
})
