package org.ktson;

import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonElement;

/**
 * Uses the standard output API exactly as a Java caller would: compiling this file proves the API
 * is reachable from Java, and JavaOutputUsageTest proves it behaves the same there.
 */
public final class JavaOutputUsage {
    private JavaOutputUsage() {
    }

    /** Basic output through the validator overload, the format selected by its token instance. */
    public static BasicOutput validateBasic(String instanceJson, String schemaJson) {
        JsonElement instance = Json.Default.parseToJsonElement(instanceJson);
        JsonSchema schema = JsonSchema.fromString(schemaJson);
        return new JsonValidator().validate(instance, schema, OutputFormat.Basic.INSTANCE);
    }

    /** Detailed output by converting a native result through the ValidationOutputs facade. */
    public static DetailedOutput toDetailed(String instanceJson, String schemaJson) {
        ValidationResult result = new JsonValidator().validate(instanceJson, schemaJson);
        return ValidationOutputs.toOutput(result, OutputFormat.Detailed.INSTANCE);
    }
}
