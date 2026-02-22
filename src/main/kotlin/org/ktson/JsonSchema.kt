package org.ktson

import kotlinx.serialization.json.*

/**
 * Represents a JSON Schema
 */
data class JsonSchema(
    val schema: JsonElement,
    val version: SchemaVersion = SchemaVersion.DRAFT_2020_12,
    val baseUri: String = "",
) {
    /**
     * Gets the $schema property if present
     */
    val schemaUri: String? = (schema as? JsonObject)?.get(SchemaKeywords.SCHEMA)?.jsonPrimitive?.contentOrNull

    /**
     * Gets the $id property if present (canonical URI for this schema)
     */
    val id: String? = (schema as? JsonObject)?.get(SchemaKeywords.ID)?.jsonPrimitive?.contentOrNull

    /**
     * Detects the schema version from the $schema property
     */
    val detectedVersion: SchemaVersion? = SchemaVersion.fromUri(schemaUri)

    /**
     * The actual version to use for validation (detected or default)
     */
    val effectiveVersion: SchemaVersion = detectedVersion ?: version

    /**
     * The effective base URI for resolving relative references
     * Uses $id if present, otherwise falls back to provided baseUri
     */
    val effectiveBaseUri: String = id ?: baseUri

    companion object {
        /**
         * Parse a JSON schema from a string
         */
        fun fromString(
            schemaJson: String,
            defaultVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12,
            baseUri: String = "",
        ): JsonSchema {
            val element = Json.parseToJsonElement(schemaJson)
            return JsonSchema(element, defaultVersion, baseUri)
        }

        /**
         * Create a schema from a JsonElement
         */
        fun fromElement(
            element: JsonElement,
            defaultVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12,
            baseUri: String = "",
        ): JsonSchema = JsonSchema(element, defaultVersion, baseUri)

		/**
		 * Extracts the $id value from a schema element if present.
		 */
		fun extractId(element: JsonElement): String? = (element as? JsonObject)?.get(SchemaKeywords.ID)?.jsonPrimitive?.contentOrNull
    }
}
