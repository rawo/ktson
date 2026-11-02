package org.ktson

/**
 * Supported JSON Schema versions
 */
enum class SchemaVersion(val uri: String, val metaSchemaUri: String) {
    DRAFT_2019_09(
        uri = "https://json-schema.org/draft/2019-09/schema",
        metaSchemaUri = "https://json-schema.org/draft/2019-09/schema",
    ),
    DRAFT_2020_12(
        uri = "https://json-schema.org/draft/2020-12/schema",
        metaSchemaUri = "https://json-schema.org/draft/2020-12/schema",
    ),
    ;

    companion object {
        /**
         * The draft whose meta-schema [uri] names, or null. Only the exact identifier matches, with or without an
         * empty fragment ("...#"): another path, query or fragment is a different schema, even when it starts with
         * a draft's identifier (#81).
         */
        @JvmStatic
        fun fromUri(uri: String?): SchemaVersion? {
            val identifier = uri?.removeSuffix("#") ?: return null
            return entries.firstOrNull { identifier == it.uri || identifier == it.metaSchemaUri }
        }
    }
}
