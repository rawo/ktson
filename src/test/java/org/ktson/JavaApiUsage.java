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

    /** A validator with format assertion switched off, the trailing arguments left at their defaults. */
    public static boolean isValidWithoutFormatAssertion(String instanceJson, String schemaJson) {
        return new JsonValidator(true, false).validate(instanceJson, schemaJson).isValid();
    }

    /** validate(instanceJson, schemaJson) with the schema version left at its default. */
    public static boolean isValid(String instanceJson, String schemaJson) {
        return new JsonValidator().validate(instanceJson, schemaJson).isValid();
    }

    /** JsonPointer.resolve without going through INSTANCE. */
    public static JsonElement resolvePointer(String documentJson, String pointer) {
        return JsonPointer.resolve(parse(documentJson), pointer);
    }

    /** JsonPointer.encodeToken without going through INSTANCE. */
    public static String encodePointerToken(String token) {
        return JsonPointer.encodeToken(token);
    }

    /** A schemaLoader passed as a plain Java lambda that serves one remote schema. */
    public static boolean isValidWithLoader(String instanceJson, String schemaJson, String remoteUri, String remoteJson) {
        JsonValidator validator = new JsonValidator(true, true, 500, uri -> uri.equals(remoteUri) ? parse(remoteJson) : null);
        return validator.validate(instanceJson, schemaJson).isValid();
    }
}
