package org.ktson

import kotlinx.serialization.json.*

/**
 * Represents a JSON Schema
 */
data class JsonSchema(
    val schema: JsonElement,
    val version: SchemaVersion = SchemaVersion.DRAFT_2020_12,
) {
    /**
     * Gets the $schema property if present
     */
    val schemaUri: String? = (schema as? JsonObject)?.get(SchemaKeywords.SCHEMA)?.jsonPrimitive?.contentOrNull

    /**
     * Detects the schema version from the $schema property
     */
    val detectedVersion: SchemaVersion? = SchemaVersion.fromUri(schemaUri)

    /**
     * The actual version to use for validation (detected or default)
     */
    val effectiveVersion: SchemaVersion = detectedVersion ?: version

    companion object {
        /**
         * Parse a JSON schema from a string
         */
        fun fromString(schemaJson: String, defaultVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12): JsonSchema {
            val element = Json.parseToJsonElement(schemaJson)
            return JsonSchema(element, defaultVersion)
        }

        /**
         * Create a schema from a JsonElement
         */
        fun fromElement(element: JsonElement, defaultVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12): JsonSchema = JsonSchema(element, defaultVersion)
    }
}
