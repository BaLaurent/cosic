import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("cosic.render.RenderCli")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    // `./gradlew :engine:test -Dcosic.parity.writeGaps=true` sets the property on the Gradle JVM; the tests run in a fork.
    systemProperty("cosic.parity.writeGaps", System.getProperty("cosic.parity.writeGaps") ?: "false")
    System.getProperty("cosic.parity.dir")?.let { systemProperty("cosic.parity.dir", it) }
    // `-Dcosic.parity.only=<regex>` runs only the cases whose id matches (and ignores known-gaps.txt: every mismatch fails).
    System.getProperty("cosic.parity.only")?.let { systemProperty("cosic.parity.only", it) }
    maxHeapSize = "2g"
    // BundledExamplesTest reads the examples of the app from disk, so Gradle must rerun the tests when they change.
    inputs.dir("../app/src/main/assets/examples")
}
