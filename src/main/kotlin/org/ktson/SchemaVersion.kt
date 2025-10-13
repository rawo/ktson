package org.ktson

/**
 * Supported JSON Schema versions
 */
enum class SchemaVersion(val uri: String, val metaSchemaUri: String) {
    DRAFT_2019_09(
        uri = "https://json-schema.org/draft/2019-09/schema",
        metaSchemaUri = "https://json-schema.org/draft/2019-09/schema"
    ),
    DRAFT_2020_12(
        uri = "https://json-schema.org/draft/2020-12/schema",
        metaSchemaUri = "https://json-schema.org/draft/2020-12/schema"
    );
    
    companion object {
        fun fromUri(uri: String?): SchemaVersion? {
            if (uri == null) return null
            return entries.firstOrNull { 
                uri.startsWith(it.uri) || uri == it.metaSchemaUri
            }
        }
    }
}

