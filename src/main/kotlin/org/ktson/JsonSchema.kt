package org.ktson

import kotlinx.serialization.json.*

/**
 * Represents a JSON Schema
 */
data class JsonSchema @JvmOverloads constructor(
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

    /**
     * The absolute URI that relative references in this schema resolve against: an absolute `$id`,
     * else `$id` resolved against [baseUri], else [baseUri]; without fragment. Null when none of
     * them is an absolute URI.
     */
    internal fun absoluteBaseUri(): String? {
        fun absolute(uri: String?): String? = uri?.substringBefore('#')?.takeIf { it.isNotEmpty() && runCatching { UriResolver.isAbsoluteUri(it) }.getOrDefault(false) }
        val base = absolute(baseUri)
        val rawId = id ?: return base
        return absolute(rawId) ?: base?.let { absolute(runCatching { UriResolver.resolveUri(it, rawId) }.getOrNull()) }
    }

    companion object {
        /**
         * Parse a JSON schema from a string
         */
        @JvmStatic
        @JvmOverloads
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
        @JvmStatic
        @JvmOverloads
        fun fromElement(
            element: JsonElement,
            defaultVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12,
            baseUri: String = "",
        ): JsonSchema = JsonSchema(element, defaultVersion, baseUri)

		/**
		 * Extracts the $id value from a schema element if present.
		 */
		@JvmStatic
		fun extractId(element: JsonElement): String? = (element as? JsonObject)?.get(SchemaKeywords.ID)?.jsonPrimitive?.contentOrNull
    }
}
