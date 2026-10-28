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
     * The $schema property, or null when it is absent or not a string. A malformed value does not stop
     * the schema from being constructed: [JsonValidator.validateSchema] reports it.
     */
    val schemaUri: String? = (schema as? JsonObject)?.stringKeyword(SchemaKeywords.SCHEMA)

    /**
     * The $id property (canonical URI for this schema), or null when it is absent or not a string.
     */
    val id: String? = extractId(schema)

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
		 * Extracts the $id value from a schema element, or null when it is absent or not a string.
		 */
		@JvmStatic
		fun extractId(element: JsonElement): String? = (element as? JsonObject)?.stringKeyword(SchemaKeywords.ID)
    }
}
