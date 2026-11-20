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
    maxHeapSize = "2g"
}
