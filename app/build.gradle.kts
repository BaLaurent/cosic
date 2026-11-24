import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun loadProperties(file: File) = Properties().apply {
    if (file.exists()) file.reader().use { load(it) }
}

val keystoreFile = rootProject.file("keystore.properties")
val keystoreProperties = loadProperties(keystoreFile)

val monetization = loadProperties(rootProject.file("monetization.properties"))
val monetizationKeys = listOf("admob.appId", "admob.bannerId", "admob.interstitialId")
val monetizationMissing = monetizationKeys.any { monetization.getProperty(it).isNullOrBlank() }

fun monetizationValue(key: String): String = monetization.getProperty(key)?.takeIf { it.isNotBlank() } ?: "MISSING"

android {
    namespace = "com.octopusman.cosic"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        // the Play package name (permanent once uploaded); the code namespace stays com.octopusman.cosic
        applicationId = "com.octopusman388.cosic"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("cosic.versionCode").get().toInt()
        versionName = providers.gradleProperty("cosic.versionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Google's public test ad units: safe to load in any build.
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
            buildConfigField("long", "INTERSTITIAL_MIN_INTERVAL_MS", "20000L")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")

            manifestPlaceholders["admobAppId"] = monetizationValue("admob.appId")
            buildConfigField("String", "ADMOB_BANNER_ID", "\"${monetizationValue("admob.bannerId")}\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${monetizationValue("admob.interstitialId")}\"")
            buildConfigField("long", "INTERSTITIAL_MIN_INTERVAL_MS", "180000L")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Release builds must carry real AdMob ids; debug builds never depend on this task.
val verifyMonetizationConfig = tasks.register("verifyMonetizationConfig") {
    val missing = monetizationMissing
    doLast {
        if (missing) throw GradleException("monetization.properties is missing admob.appId/bannerId/interstitialId")
    }
}

// A Play bundle must be signed and carry real ad ids; assembleRelease stays usable with test ids for emulator tours.
val verifyReleaseBundle = tasks.register("verifyReleaseBundle") {
    val unsigned = !keystoreFile.exists()
    val testIds = monetizationKeys.any { monetization.getProperty(it).orEmpty().startsWith("ca-app-pub-3940256099942544") }
    doLast {
        if (unsigned) throw GradleException("bundleRelease needs keystore.properties (storeFile, storePassword, keyAlias, keyPassword) at the project root")
        if (testIds) throw GradleException("monetization.properties holds Google's test ad ids; put the real AdMob ids before building a Play bundle")
    }
}

tasks.configureEach {
    if (name in setOf("preReleaseBuild", "assembleRelease", "bundleRelease")) dependsOn(verifyMonetizationConfig)
    if (name == "bundleRelease") dependsOn(verifyReleaseBundle)
}

dependencies {
    implementation(project(":engine"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.billing.ktx)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.usb.serial)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// ExamplesEvaluateTest reads these files from disk, so Gradle must rerun the unit tests when they change.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets/examples")
    inputs.dir("src/main/assets/styles")
}
