package org.ktson;

import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonElement;

/**
 * Uses KtSON's public API exactly as a Java caller would: compiling this file proves each call is
 * reachable from Java in its short form, and JavaApiUsageTest proves it behaves as in Kotlin.
 */
public final class JavaApiUsage {
    private JavaApiUsage() {
    }

    static JsonElement parse(String json) {
        return Json.Default.parseToJsonElement(json);
    }

    /** JsonSchema.fromString with every default left out. */
    public static JsonSchema schemaFromString(String schemaJson) {
        return JsonSchema.fromString(schemaJson);
    }

    /** JsonSchema.fromString with the version given and the base URI left out. */
    public static JsonSchema schemaFromString(String schemaJson, SchemaVersion version) {
        return JsonSchema.fromString(schemaJson, version);
    }

    /** JsonSchema.fromElement with every default left out. */
    public static JsonSchema schemaFromElement(String schemaJson) {
        return JsonSchema.fromElement(parse(schemaJson));
    }

    /** The JsonSchema constructor with every default left out. */
    public static JsonSchema schemaFromConstructor(String schemaJson) {
        return new JsonSchema(parse(schemaJson));
    }

    /** JsonSchema.extractId without going through Companion. */
    public static String extractId(String schemaJson) {
        return JsonSchema.extractId(parse(schemaJson));
    }
}
