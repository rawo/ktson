# Spec: RFC 6901 instance locations in validation errors

**Status**: proposed, not implemented
**Depends on**: nothing (keyword locations in `ValidationError.schemaPath` are already implemented)
**Blocks**: [SPEC_OUTPUT_FORMATS.md](SPEC_OUTPUT_FORMATS.md)

## Motivation

`ValidationError.path` locates the failing *instance* value. It currently uses a home-grown
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

## Current behaviour

Paths are assembled inline at roughly a dozen sites in `JsonValidator.kt`, all of the same two shapes:

```kotlin
val propPath = if (path.isEmpty()) propName else "$path.$propName"
val itemPath = "$path[$index]"
```

The root instance is `""`. One site produces the pseudo-segment `"$path.<propertyName>"` for
`propertyNames` failures, which is not a location at all.

| Instance | Current `path` | Proposed `instanceLocation` |
|---|---|---|
| root | `""` | `""` |
| `{"user": {"age": …}}` | `user.age` | `/user/age` |
| `[1, "two"]` element 1 | `[1]` | `/1` |
| `{"a.b": …}` | `a.b` | `/a.b` |
| `{"a/b": …}` | `a/b` | `/a~1b` |
| `{"a~b": …}` | `a~b` | `/a~0b` |

## Design

1. Add a private helper mirroring the existing keyword-location helpers:

   ```kotlin
   /** Child location of [path] for an object member or array index (RFC 6901). */
   private fun childOf(path: String, token: String): String = "$path/${JsonPointer.encodeToken(token)}"
   ```

2. Replace every `"$path.$propName"` with `childOf(path, propName)` and every `"$path[$index]"`
   with `childOf(path, index.toString())`. The `if (path.isEmpty())` special case disappears —
   a JSON Pointer always leads with `/`, and the root is the empty string.

3. `propertyNames` failures report the location of the *object* being validated, not a fabricated
   `<propertyName>` child. The failing key belongs in the message, which already names it.

4. Keep `validateSchemaStructure`'s paths in the same notation as instance paths for consistency;
   they describe positions in the schema document and should use pointers too.

## API impact

`ValidationError.path` is public on a released 1.0.0, so changing its format is a breaking change
for anyone parsing or displaying it. Two options:

**Option A — rename and deprecate (recommended).** Add `instanceLocation: String` holding the
pointer; keep `path` as a deprecated computed property that derives the old notation (a lossy
back-conversion is acceptable for a deprecated accessor). Remove `path` in 2.0.0.

**Option B — change `path` in place.** Smaller diff, no dead code, but silently breaks consumers
at a patch/minor version. Only acceptable if the library is known to have no external users yet.

Pick one before implementing; the rest of the work is identical.

## Test plan

- Unit tests in `ErrorMessageTest.kt`: root, nested object, array index, deeply mixed
  (`/users/0/roles/2`), and the three escaping cases (`.`, `/`, `~` in a property name).
- A regression test pinning the disambiguation: the two schemas in *Motivation* must now produce
  different locations.
- The official suite does not assert error locations, so it will neither catch nor block this.
- `EdgeCaseAndThreadSafetyTest` covers instances with unusual keys; check none assert on `path`.

## Effort

Small — roughly a dozen construction sites, one helper, plus the deprecation shim. The risk is in
the API decision, not the code.

## References

- RFC 6901, JSON Pointer
- JSON Schema Core 2020-12 §12.3.2 (`instanceLocation` is a JSON Pointer)
- `src/main/kotlin/org/ktson/JsonPointer.kt` — `encodeToken`
