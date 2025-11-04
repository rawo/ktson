plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    `maven-publish`
}

group = "org.ktson"
version = "0.0.2-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin coroutines for suspend functions
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
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
