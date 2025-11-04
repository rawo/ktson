# Remote References Implementation Plan

## Executive Summary

**Status**: Not Implemented
**Complexity**: HIGH
**Effort Estimate**: 40-60 hours
**Risk Level**: MEDIUM-HIGH (Security, Performance, Complexity)

This document provides a critical analysis and comprehensive implementation plan for adding HTTP/HTTPS remote reference support to the KtSON JSON Schema validator.

## Current State Analysis

### What Works
- ✅ Local references (`#/...`, `#/$defs/...`) fully functional
- ✅ Thread-safe stateless ReferenceResolver
- ✅ Depth limiting prevents infinite recursion
- ✅ Fragment resolution via JsonPointer

### Critical Gaps
- ❌ **No $id keyword support** - Cannot identify schemas by URI
- ❌ **No base URI tracking** - Cannot resolve relative references
- ❌ **No HTTP client** - Cannot fetch remote schemas
- ❌ **No caching mechanism** - Would refetch schemas repeatedly
- ❌ **No security controls** - Vulnerable to SSRF, DoS, malicious schemas
- ❌ **No URI normalization** - Cannot handle URI variations correctly
- ❌ **No async fetching** - Synchronous API would block on network calls
- ❌ **No schema validation** - Could load invalid schemas
- ❌ **No circular reference detection** - Remote schemas can create cycles
- ❌ **No timeout/retry** - Network calls could hang indefinitely

### Code Locations
- `JsonPointer.kt:87-102` - ReferenceResolver.resolveRef() (returns null for HTTP/HTTPS)
- `JsonValidator.kt:151,165,179` - Three call sites for resolveRef()
- `JsonSchema.kt` - No $id extraction or base URI support
- `SchemaVersion.kt` - Contains meta-schema URIs (would need remote fetching)

## Technical Requirements

### 1. URI Resolution & Identification

**JSON Schema URI Specifications:**
- **$id keyword** (Draft 2019-09+): Declares the canonical URI for a schema
- **$schema keyword**: References meta-schema (could be remote)
- **Base URI**: Current schema's base URI for resolving relative refs
- **URI Resolution**: RFC 3986 compliant resolution (absolute, relative, fragments)

**Required Capabilities:**
```kotlin
// Example schema with $id
{
  "$id": "https://example.com/schemas/person.json",
  "type": "object",
  "properties": {
    "address": { "$ref": "address.json" },  // Relative to base URI
    "employer": { "$ref": "https://example.com/schemas/company.json" },  // Absolute
    "manager": { "$ref": "#/$defs/person" }  // Fragment (local)
  }
}
```

**Implementation Needs:**
1. Extract and track `$id` from all schemas
2. Build base URI from `$id` or parent schema URI
3. Resolve relative URIs against base URI (RFC 3986)
4. Normalize URIs (remove fragments, handle encoding)
5. Track URI → JsonElement mapping for retrieval

### 2. HTTP Client Requirements

**Constraints:**
- Kotlin JVM targeting Java 21
- Already has kotlinx-coroutines dependency
- Synchronous validator API (should remain synchronous)
- Thread-safe usage required

**CRITICAL: Why NOT khttp or Fuel?**

⚠️ **khttp is ABANDONED and BROKEN on Java 16+**
- Last commit: June 2020 (4+ years ago)
- Contains unpatched security vulnerabilities
- Fails on Java 16+ with InaccessibleObjectException
- Original artifact removed from Maven Central

⚠️ **Fuel is in AWKWARD TRANSITION**
- Stable (v2.3.0): Uses Kotlin 1.4.0 (incompatible with our 2.2.20)
- Modern (v3.0.0-alpha04): ALPHA quality, not production-ready
- Heavier than Ktor (~2MB vs 700KB)
- Android-focused, not optimized for server JVM

See [HTTP_CLIENT_ANALYSIS.md](HTTP_CLIENT_ANALYSIS.md) for detailed analysis.

**Options Analysis:**

| Option | Pros | Cons | Verdict |
|--------|------|------|---------|
| **Ktor Client** | Kotlin-native, coroutines, actively maintained, secure, lightweight | Additional dependency (~700KB) | ✅ **RECOMMENDED** |
| **Java HttpClient** (JDK 11+) | Built-in, no dependency, secure | Less Kotlin-friendly, verbose API | ⚠️ Acceptable fallback |
| **Fuel** | Simple API, Kotlin-friendly | ❌ Version confusion, 2.x outdated, 3.x alpha | ❌ **NOT NOW** (maybe in 6-12mo) |
| **khttp** | Simple API | ❌ ABANDONED, broken on Java 16+, insecure | ❌ **NEVER USE** |
| **OkHttp** | Battle-tested, good caching | Large dependency (~2MB), overkill | ❌ Too heavy |

**Recommended: Ktor Client**
```kotlin
dependencies {
    implementation("io.ktor:ktor-client-core:2.3.7")        // ~500 KB
    implementation("io.ktor:ktor-client-cio:2.3.7")         // ~200 KB (engine)
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")  // Optional
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")  // Optional
}
// Total: ~700 KB (core + CIO engine)
```

**Why Ktor over khttp:**
- ✅ Actively maintained by JetBrains (security updates)
- ✅ Works on Java 21 (khttp fails on Java 16+)
- ✅ Native coroutines support (future-proof)
- ✅ Can be used synchronously with runBlocking
- ✅ Built-in timeout/retry/redirect handling
- ✅ Smaller total size (~700 KB vs khttp's ~1.5 MB)

**See [HTTP_CLIENT_ANALYSIS.md](HTTP_CLIENT_ANALYSIS.md) for complete comparison.**

### 3. Caching Strategy

**Critical Requirements:**
- Thread-safe concurrent access
- Size limits to prevent memory exhaustion
- TTL (Time To Live) for cache entries
- Cache key: Normalized URI (without fragment)
- Must handle concurrent fetches (don't fetch same URI twice)

**Cache Architecture:**

```kotlin
/**
 * Thread-safe schema cache with size limits and TTL
 */
class SchemaCache(
    private val maxEntries: Int = 100,
    private val ttlMillis: Long = 3600_000, // 1 hour
) {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    data class CacheEntry(
        val schema: JsonElement,
        val timestamp: Long,
        val baseUri: String,
    )

    suspend fun getOrFetch(
        uri: String,
        fetcher: suspend (String) -> JsonElement
    ): JsonElement {
        // Check cache first
        cache[uri]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < ttlMillis) {
                return entry.schema
            }
            cache.remove(uri)
        }

        // Acquire lock for this URI to prevent duplicate fetches
        val mutex = fetchLocks.computeIfAbsent(uri) { Mutex() }
        mutex.withLock {
            // Double-check after acquiring lock
            cache[uri]?.let { return it.schema }

            // Fetch and cache
            val schema = fetcher(uri)

            // Evict old entries if needed
            if (cache.size >= maxEntries) {
                evictOldest()
            }

            cache[uri] = CacheEntry(schema, System.currentTimeMillis(), uri)
            return schema
        }
    }
}
```

**Cache Invalidation:**
- TTL-based expiration
- LRU eviction when size limit reached
- Manual clearing via new API method

### 4. Security & Validation

**CRITICAL SECURITY ISSUES:**

1. **SSRF (Server-Side Request Forgery)**
   - Attacker provides schema with $ref to internal network
   - Example: `{"$ref": "http://localhost:8080/admin/delete"}`
   - **Impact**: Access to internal services, data exfiltration

2. **Schema Bomb (DoS)**
   - Deeply nested remote references causing resource exhaustion
   - Example: Chain of 1000 remote schemas
   - **Impact**: Memory exhaustion, CPU overload

3. **Malicious Schema Content**
   - Remote schema with extreme complexity
   - Very large schemas (GB size)
   - **Impact**: Memory exhaustion, validation hang

4. **DNS Rebinding**
   - DNS resolution changes between check and fetch
   - **Impact**: Bypass allowlist checks

**Required Security Controls:**

```kotlin
data class RemoteRefConfig(
    // Security: URI filtering
    val allowedDomains: Set<String> = emptySet(),  // Empty = allow all (DANGEROUS!)
    val blockedDomains: Set<String> = setOf(
        "localhost", "127.0.0.1", "0.0.0.0",
        "169.254.0.0/16",  // Link-local
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",  // Private networks
    ),

    // Security: Resource limits
    val maxSchemaSize: Long = 10 * 1024 * 1024,  // 10MB max
    val maxRedirects: Int = 5,
    val maxRemoteDepth: Int = 10,  // Max chain of remote references

    // Performance: Timeouts
    val connectTimeoutMs: Long = 5000,  // 5 sec
    val readTimeoutMs: Long = 10000,    // 10 sec

    // Caching
    val enableCaching: Boolean = true,
    val maxCacheEntries: Int = 100,
    val cacheTtlMs: Long = 3600_000,  // 1 hour

    // Validation
    val validateRemoteSchemas: Boolean = true,  // Validate structure before use
)
```

**Validation Flow:**
```
1. Extract URI from $ref
2. Normalize URI (remove fragment, decode, lowercase host)
3. Check against blocklist/allowlist
4. Check cache
5. If not cached:
   a. Validate URI scheme (only http/https)
   b. Validate not private IP range
   c. Fetch with timeout
   d. Check response size
   e. Parse JSON
   f. Validate schema structure (if enabled)
   g. Cache result
6. Resolve fragment (if any)
7. Return resolved schema
```

### 5. URI Resolution Algorithm

**RFC 3986 Compliance Required:**

```kotlin
/**
 * Resolves a reference URI against a base URI
 * Implements RFC 3986 Section 5.2
 */
fun resolveUri(baseUri: String, refUri: String): String {
    // If ref is absolute, return it
    if (refUri.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:"))) {
        return normalizeUri(refUri)
    }

    // Parse base URI
    val base = parseUri(baseUri)

    // If ref starts with //, use base scheme
    if (refUri.startsWith("//")) {
        return normalizeUri("${base.scheme}:$refUri")
    }

    // If ref starts with /, use base scheme + authority
    if (refUri.startsWith("/")) {
        return normalizeUri("${base.scheme}://${base.authority}$refUri")
    }

    // Relative path - merge with base path
    val basePath = base.path.substringBeforeLast('/', "")
    val resolved = "${base.scheme}://${base.authority}/$basePath/$refUri"

    return normalizeUri(resolved)
}

fun normalizeUri(uri: String): String {
    // Remove fragment for cache key
    // Decode percent-encoded characters
    // Normalize path (remove /./, /../)
    // Lowercase scheme and host
}
```

### 6. Base URI Tracking

**Challenge**: Need to track base URI context through validation

**Current Signature:**
```kotlin
private fun validateElement(
    instance: JsonElement,
    schemaElement: JsonElement,
    path: String,
    errors: MutableList<ValidationError>,
    version: SchemaVersion,
    rootSchema: JsonElement,
    depth: Int = 0,
)
```

**Proposed Change:**
```kotlin
private fun validateElement(
    instance: JsonElement,
    schemaElement: JsonElement,
    path: String,
    errors: MutableList<ValidationError>,
    version: SchemaVersion,
    rootSchema: JsonElement,
    depth: Int = 0,
    baseUri: String = "",  // NEW: base URI for resolving relative refs
)
```

**Base URI Resolution Rules:**
1. If schema has `$id`, use it as new base URI
2. Otherwise, inherit base URI from parent schema
3. Root schema base URI comes from:
   - Explicit parameter to validate()
   - OR extracted from schema's $id
   - OR empty string (all refs must be absolute)

### 7. Async vs Sync API

**Critical Constraint**: Current API is synchronous

```kotlin
fun validate(instance: JsonElement, schema: JsonSchema): ValidationResult
```

**Problem**: Network calls are inherently async/blocking

**Options:**

| Approach | Pros | Cons | Verdict |
|----------|------|------|---------|
| **Pre-fetch all remote schemas** | Keeps sync API, no blocking during validation | Requires knowing all refs upfront, complex | ✅ **RECOMMENDED** |
| **Blocking fetch during validation** | Simple implementation | Blocks threads, poor performance | ⚠️ Simple but slow |
| **Add async validate() overload** | Best performance | API breaking change | ❌ Future consideration |

**Recommended: Pre-fetch + Cache**
```kotlin
class JsonValidator(
    // ... existing params ...
    private val remoteRefConfig: RemoteRefConfig = RemoteRefConfig(),
) {
    private val schemaCache = SchemaCache(
        maxEntries = remoteRefConfig.maxCacheEntries,
        ttlMillis = remoteRefConfig.cacheTtlMs,
    )

    /**
     * Pre-fetch all remote references in a schema (async)
     * Call this before validate() for best performance
     */
    suspend fun prefetchRemoteReferences(schema: JsonSchema) {
        val remoteRefs = extractRemoteReferences(schema.schema)
        remoteRefs.forEach { uri ->
            schemaCache.getOrFetch(uri) { fetchSchema(it) }
        }
    }

    /**
     * Synchronous validation (uses cache, blocks if schema not cached)
     */
    fun validate(
        instance: JsonElement,
        schema: JsonSchema,
        baseUri: String = "",  // NEW
    ): ValidationResult {
        // If cache miss during validation, block on fetch
        // (but user should have called prefetchRemoteReferences)
    }
}
```

### 8. Circular Reference Detection

**Problem**: Remote schemas can reference each other circularly

```
https://example.com/A.json
  → $ref: "B.json"

https://example.com/B.json
  → $ref: "A.json"  // Circular!
```

**Solution**: Track URI resolution stack

```kotlin
private val resolutionStack = ThreadLocal<MutableSet<String>>()

fun resolveRemoteRef(uri: String): JsonElement? {
    val stack = resolutionStack.get() ?: mutableSetOf<String>().also {
        resolutionStack.set(it)
    }

    val normalizedUri = normalizeUri(uri)

    if (normalizedUri in stack) {
        // Circular reference detected!
        throw CircularReferenceException("Circular reference detected: $normalizedUri")
    }

    stack.add(normalizedUri)
    try {
        return fetchAndResolve(normalizedUri)
    } finally {
        stack.remove(normalizedUri)
    }
}
```

## Implementation Plan

### Phase 1: Foundation (8-12 hours)

**Goal**: URI resolution infrastructure

**Tasks:**
1. Add `$id` keyword to SchemaKeywords
2. Implement `extractId()` in JsonSchema
3. Implement RFC 3986 URI resolution (resolveUri, normalizeUri, parseUri)
4. Add unit tests for URI resolution (50+ test cases)
5. Update JsonSchema to track baseUri

**Files to Modify:**
- `SchemaKeywords.kt` - Add ID constant
- `JsonSchema.kt` - Add id, baseUri properties
- New: `UriResolver.kt` - URI resolution logic
- New: `UriResolverTest.kt` - Comprehensive tests

**Acceptance Criteria:**
- ✅ Can extract $id from schemas
- ✅ Can resolve relative URIs against base
- ✅ Can normalize URIs correctly
- ✅ 100% test coverage on URI logic

### Phase 2: HTTP Client & Fetching (8-12 hours)

**Goal**: Fetch remote schemas safely

**Tasks:**
1. Add Ktor client dependency
2. Implement RemoteRefConfig data class
3. Implement SchemaFetcher with security controls
4. Implement URI allowlist/blocklist filtering
5. Add timeout and size limit enforcement
6. Add comprehensive tests (including malicious cases)

**Files to Create:**
- `RemoteRefConfig.kt` - Configuration
- `SchemaFetcher.kt` - HTTP fetching with security
- `SchemaFetcherTest.kt` - Security tests

**Acceptance Criteria:**
- ✅ Can fetch remote JSON schemas
- ✅ Blocks private IP ranges
- ✅ Enforces size limits
- ✅ Enforces timeouts
- ✅ Handles HTTP errors gracefully

### Phase 3: Caching (6-8 hours)

**Goal**: Thread-safe schema cache

**Tasks:**
1. Implement SchemaCache with ConcurrentHashMap
2. Implement TTL-based expiration
3. Implement LRU eviction
4. Implement fetch deduplication (Mutex per URI)
5. Add cache statistics/monitoring
6. Add comprehensive concurrency tests

**Files to Create:**
- `SchemaCache.kt` - Cache implementation
- `SchemaCacheTest.kt` - Thread-safety tests

**Acceptance Criteria:**
- ✅ Thread-safe concurrent access
- ✅ Prevents duplicate fetches
- ✅ Respects size limits
- ✅ Respects TTL
- ✅ Tested with 100+ concurrent threads

### Phase 4: Integration (10-15 hours)

**Goal**: Integrate into validator

**Tasks:**
1. Update ReferenceResolver to use cache + fetcher
2. Add baseUri parameter to validateElement() (propagate through all 21 call sites)
3. Implement circular reference detection
4. Add prefetchRemoteReferences() method
5. Update constructor to accept RemoteRefConfig
6. Update error handling for remote ref failures

**Files to Modify:**
- `JsonPointer.kt` - Update ReferenceResolver
- `JsonValidator.kt` - Add baseUri tracking, remote ref support
- `ValidationError.kt` - Add remote ref error types

**Acceptance Criteria:**
- ✅ Can resolve remote $ref
- ✅ Detects circular references
- ✅ Base URI properly tracked
- ✅ Errors are descriptive

### Phase 5: Testing & Documentation (8-12 hours)

**Goal**: Comprehensive tests and docs

**Tasks:**
1. Create RemoteReferencesTest with 50+ test cases
2. Test with official JSON Schema remote ref tests
3. Test security controls (SSRF, DoS, etc.)
4. Performance testing (1000s of remote refs)
5. Update README with remote ref examples
6. Update CLAUDE.md with remote ref architecture
7. Create REMOTE_REFERENCES_GUIDE.md
8. Update IMPLEMENTATION_STATUS.md

**Files to Create/Modify:**
- `RemoteReferencesTest.kt` - Comprehensive tests
- `README.md` - Usage examples
- `CLAUDE.md` - Architecture notes
- `docs/REMOTE_REFERENCES_GUIDE.md` - User guide

**Acceptance Criteria:**
- ✅ 100+ test cases passing
- ✅ Security tests prevent all attack vectors
- ✅ Performance acceptable (<100ms for cached refs)
- ✅ Documentation complete

## Risk Assessment

### High Risks

**1. Security Vulnerabilities** (Impact: CRITICAL, Likelihood: HIGH)
- **Risk**: SSRF, DNS rebinding, malicious schemas
- **Mitigation**: Strict allowlist/blocklist, size limits, validation
- **Residual Risk**: Medium (requires careful configuration)

**2. Performance Degradation** (Impact: HIGH, Likelihood: MEDIUM)
- **Risk**: Blocking on network calls, cache misses
- **Mitigation**: Pre-fetching, aggressive caching, timeouts
- **Residual Risk**: Low (with proper usage)

**3. Memory Exhaustion** (Impact: HIGH, Likelihood: MEDIUM)
- **Risk**: Large schemas, cache bloat
- **Mitigation**: Size limits, cache eviction, TTL
- **Residual Risk**: Low (with limits enforced)

### Medium Risks

**4. API Complexity** (Impact: MEDIUM, Likelihood: HIGH)
- **Risk**: Users must configure security correctly
- **Mitigation**: Safe defaults, clear documentation, examples
- **Residual Risk**: Medium (education needed)

**5. Breaking Changes** (Impact: MEDIUM, Likelihood: LOW)
- **Risk**: Signature changes to internal methods
- **Mitigation**: Internal APIs only, default parameters
- **Residual Risk**: Very Low

## Alternative Approaches

### Alternative 1: Compile-Time Schema Bundling
**Concept**: Bundle all referenced schemas at compile time
- **Pros**: No runtime fetching, no security issues, fast
- **Cons**: Not flexible, requires build tooling
- **Verdict**: ❌ Too limited for general use

### Alternative 2: Schema Registry Pattern
**Concept**: Users pre-register all schemas with URIs
- **Pros**: No HTTP client needed, full control
- **Cons**: Poor UX, doesn't match JSON Schema spec
- **Verdict**: ✅ Could offer as alternative API

### Alternative 3: Plugin System
**Concept**: Users provide custom schema loader
- **Pros**: Maximum flexibility, no HTTP dependency
- **Cons**: Complex API, users must implement security
- **Verdict**: ✅ Could offer as extension point

## Recommendations

### For v1.0 Release
**DO NOT IMPLEMENT** - Too complex, security-sensitive
- Mark as explicitly out of scope
- Document workarounds (pre-bundle schemas, use schema registry pattern)

### For v1.1+ Release
**IMPLEMENT WITH CAUTION**
1. Start with Phase 1 & 2 (URI + Fetching) in v1.1
2. Add Phase 3 (Caching) in v1.2
3. Complete Phases 4 & 5 in v1.3
4. Mark as BETA for 2-3 releases
5. Require explicit opt-in via RemoteRefConfig

### Critical Success Factors
1. ✅ Security by default (deny-by-default allowlist)
2. ✅ Clear documentation of risks
3. ✅ Comprehensive security testing
4. ✅ Performance testing at scale
5. ✅ Gradual rollout with beta period

## Estimated Effort

| Phase | Hours | Complexity |
|-------|-------|------------|
| Phase 1: Foundation | 8-12 | Medium |
| Phase 2: HTTP Client | 8-12 | High (security) |
| Phase 3: Caching | 6-8 | Medium |
| Phase 4: Integration | 10-15 | High (many touchpoints) |
| Phase 5: Testing/Docs | 8-12 | Medium |
| **TOTAL** | **40-59 hours** | **HIGH** |

**Additional Considerations:**
- Security review: +8 hours
- Performance optimization: +4-8 hours
- Beta testing period: 4-6 weeks
- Bug fixes: +10-20 hours

**TOTAL WITH MARGIN: 60-95 hours**

## Conclusion

Remote reference support is a **high-value, high-complexity, high-risk** feature. The implementation requires:

1. ✅ Strong security controls (non-negotiable)
2. ✅ Robust caching (required for performance)
3. ✅ Careful API design (backward compatibility)
4. ✅ Extensive testing (security + performance)
5. ✅ Clear documentation (users must understand risks)

**Recommendation**: Defer to post-1.0 release. Mark as BETA when implemented. Require explicit opt-in with security acknowledgment.

**Alternative**: Offer schema registry API as simpler, safer alternative for users who need multi-schema validation.
