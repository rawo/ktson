plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    `maven-publish`
}

group = "org.ktson"
version = "3.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // JSON serialization. `api`, not `implementation`: JsonElement is part of KtSON's public API
    // (JsonSchema.schema, validate(JsonElement, ...), schemaLoader), so consumers need it to compile.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Testing
    testImplementation(kotlin("test"))
    // Coroutines are used only by the tests (Kotest); the validator API is synchronous
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.kotest:kotest-runner-junit5:5.7.2")
    testImplementation("io.kotest:kotest-assertions-core:5.7.2")
    testImplementation("io.mockk:mockk:1.13.8")
}

tasks.test {
    useJUnitPlatform()
    // Exclude performance tests by default
    filter {
        excludeTestsMatching("org.ktson.PerformanceTest")
    }
    // Increase memory for tests
    minHeapSize = "512m"
    maxHeapSize = "2g"
    // Show test output
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
}

// Create a separate task for performance tests
tasks.register<Test>("performanceTest") {
    description = "Runs performance tests"
    group = "verification"

    useJUnitPlatform()

    // Include only performance tests
    filter {
        includeTestsMatching("org.ktson.PerformanceTest")
    }

    // Set test classpath
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    // Increase memory for performance tests
    minHeapSize = "512m"
    maxHeapSize = "2g"

    // Show test output
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }

    shouldRunAfter(tasks.test)
}

kotlin {
    jvmToolchain(21)
}

// A sources JAR next to the main one, attached to GitHub releases (and included when publishing locally).
java {
    withSourcesJar()
}

ktlint {
    version.set("1.7.1")
    android.set(false)

    // Use IntelliJ IDEA code style
    filter {
        exclude("**/generated/**")
        include("**/kotlin/**")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

// JsonElement is part of the public API, so the published POM must give consumers
// kotlinx-serialization-json at compile scope (an `implementation` dependency becomes runtime scope).
val verifyPublishedApiDependencies by tasks.registering {
    description = "Fails if the published POM does not expose kotlinx-serialization-json at compile scope"
    group = "verification"
    dependsOn("generatePomFileForMavenPublication")
    val pomFile = layout.buildDirectory.file("publications/maven/pom-default.xml")
    doLast {
        val pom = pomFile.get().asFile.readText()
        val exposed = Regex("<artifactId>kotlinx-serialization-json-jvm</artifactId>\\s*<version>[^<]+</version>\\s*<scope>compile</scope>")
        check(exposed.containsMatchIn(pom)) { "kotlinx-serialization-json must be an api dependency: JsonElement is in KtSON's public API" }
    }
}

tasks.named("check") { dependsOn(verifyPublishedApiDependencies) }
