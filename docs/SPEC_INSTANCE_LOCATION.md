# Spec: RFC 6901 instance locations in validation errors

**Status**: implemented, unreleased (Option A)
**Depends on**: nothing (keyword locations in `ValidationError.schemaPath` are already implemented)
**Unblocks**: [SPEC_OUTPUT_FORMATS.md](SPEC_OUTPUT_FORMATS.md)

## Motivation

`ValidationError.path` locates the failing *instance* value. It previously used a home-grown
dotted/bracket notation, which is both non-standard and ambiguous.

The ambiguity is real, not theoretical. These two schemas produce the identical `path` of `a.b`
for two completely different instance locations:

```json
{"properties": {"a.b":  {"type": "integer"}}}          // instance {"a.b": "x"}   -> path "a.b"
{"properties": {"a": {"properties": {"b": {…}}}}}      // instance {"a": {"b": "x"}} -> path "a.b"
```

A consumer cannot tell which value to highlight. The same applies to any property name containing
`.`, `[` or `]` — common in JSON that carries file names, versions or MIME types as keys.

RFC 6901 JSON Pointer has no such ambiguity, is what the JSON Schema output formats mandate for
`instanceLocation`, and is already implemented in this repository (`JsonPointer.encodeToken`
escapes `~` as `~0` and `/` as `~1`).

## Previous behaviour

Paths were assembled inline at roughly a dozen sites in `JsonValidator.kt`, all of the same two shapes:

```kotlin
val propPath = if (path.isEmpty()) propName else "$path.$propName"
val itemPath = "$path[$index]"
```

The root instance is `""`. One site produced the pseudo-segment `"$path.<propertyName>"` for
`propertyNames` failures, which is not a location at all.

| Instance | Previous `path` | Implemented `instanceLocation` |
|---|---|---|
| root | `""` | `""` |
| `{"user": {"age": …}}` | `user.age` | `/user/age` |
| `[1, "two"]` element 1 | `[1]` | `/1` |
| `{"a.b": …}` | `a.b` | `/a.b` |
| `{"a/b": …}` | `a/b` | `/a~1b` |
| `{"a~b": …}` | `a~b` | `/a~0b` |

## Design

1. Added a private helper mirroring the existing keyword-location helpers:

   ```kotlin
   /** Child location of [path] for an object member or array index (RFC 6901). */
   private fun childOf(path: String, token: String): String = "$path/${JsonPointer.encodeToken(token)}"
   ```

2. Replaced every `"$path.$propName"` with `childOf(path, propName)` and every `"$path[$index]"`
   with `childOf(path, index.toString())`. The `if (path.isEmpty())` special case disappears —
   a JSON Pointer always leads with `/`, and the root is the empty string.

3. `propertyNames` failures report the location of the *object* being validated, not a fabricated
   `<propertyName>` child. Each top-level name error prefixes the message with the failing key, including errors from
   boolean schemas and combiners; nested causes retain the owning object's pointer.

4. `validateSchemaStructure` uses pointers to the schema node being checked. The keyword path
   preserves the correct map keyword (`properties`, `patternProperties`, `dependentSchemas`) and
   the keyword before a combiner index.

5. `JsonPointer.resolve` distinguishes the root (`""` or `"#"`) from an empty member (`"/"` or
   `"#/"`) and preserves consecutive empty tokens. Pointer decoding is shared by resolution and
   the deprecated display accessor.

## API decision and migration

**Selected Option A:** `instanceLocation` is the first property of `ValidationError`; `path` is a
`@Deprecated` computed getter. `toString()` prints the canonical pointer, including nested causes.
The project is already at 2.0.0, so the original suggestion to remove `path` in 2.0.0 is obsolete.
Removal is deferred to a future major release, with no specific version assigned.

This is not full source or semantic compatibility:

- Existing `error.path` reads still compile with a deprecation warning, but the projection is
  inherently lossy. Dotted and bracketed keys remain ambiguous. Numeric tokens are rendered as
  `[index]` (including numeric object keys), and empty tokens lose key identity.
- Replace named constructor `path = ...` and `copy(path = ...)` arguments with
  `instanceLocation = ...`, supplying a JSON Pointer rather than a dotted string.
- Positional construction and `component1()`/destructuring now carry pointers; callers must migrate
  the values they supply or consume. The positional constructor shape alone does not preserve
  the old semantics.
- Error rendering and `propertyNames` locations change. Package this API change in a future major
  release; this implementation does not change `build.gradle.kts`'s version.

```kotlin
val error = ValidationError(instanceLocation = "/users/0/age", message = "Expected an integer")
val relocated = error.copy(instanceLocation = "/users/1/age")
// error.instanceLocation == "/users/0/age"
// error.path == "users[0].age" (deprecated; display only)
```

The root pointer is `""`; an empty property key is `/`; a literal `~1` key is `/~01`.
`required`, `contains`, and object/array-level failures retain the owning instance location.
Subschema validation failures point to the child value being validated. Paths do not change when
following `$ref` or evaluating a combiner; `schemaPath` records those schema-side hops separately.

## Verification

`InstanceLocationTest.kt` contains 117 tests covering both drafts, root and nested instances,
arrays and tuple/prefix items, mixed `/users/0/roles/2` paths, special/empty/numeric/Unicode keys,
pattern/additional/unevaluated keywords, conditionals, references, depth errors, nested causes,
`propertyNames`, schema-structure paths, constructor/copy/destructuring semantics, the deprecated
projection, `toString()` and JSON Pointer empty-key resolution.

The dotted-key regression distinguishes `/a.b` from `/a/b`. Special-key tests resolve each emitted
pointer back into the original instance. Schema errors resolve into the schema document. Existing
examples, official-suite failure output and edge-case assertions use `instanceLocation`.

Full standard test run: 5,024 passing tests (4,314 official assertions and 710 custom tests).
`ktlintFormat`, `ktlintCheck` and a subagent code review are part of the completion checks.
The official suite checks verdicts rather than output locations, so dedicated location tests
provide the regression coverage for this feature.

## References

- RFC 6901, JSON Pointer
- JSON Schema Core 2020-12 §12.3.2 (`instanceLocation` is a JSON Pointer)
- `src/main/kotlin/org/ktson/JsonPointer.kt` — `encodeToken`
