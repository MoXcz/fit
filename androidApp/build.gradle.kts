// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val abiVersionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)

android {
    namespace = "app.fit.android"
    compileSdk = libs.versions.app.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.fit"
        minSdk = libs.versions.app.minSdk.get().toInt()
        targetSdk = libs.versions.app.targetSdk.get().toInt()
        versionCode = libs.versions.app.versionCode.get().toInt()
        versionName =
            libs.versions.app.versionName
                .get()
    }

    // Personal release key from the git-ignored keystore.properties at the repo root
    // (docs/building.md). Without it, release builds stay unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning =
        if (keystoreFile.exists()) {
            val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }

            fun prop(key: String): String = requireNotNull(props.getProperty(key)) { "keystore.properties: missing $key" }
            signingConfigs.create("release") {
                storeFile = file(prop("storeFile"))
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
            }
        } else {
            null
        }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Also checks :shared's Android code.
        checkDependencies = true
    }

    // One APK per CPU type plus a universal one, for GitHub releases.
    splits {
        abi {
            isEnable = true
            reset()
            // x86_64 is in the map; lint can't read the spread.
            //noinspection ChromeOsAbiSupport
            include(*abiVersionCodes.keys.toTypedArray())
            isUniversalApk = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Built-in content ships as assets under content/ (docs/engineering.md §3); the app loads it on
// first start and whenever it changes.
abstract class BundleContent : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        source.get().asFile.copyRecursively(out.resolve("content"))
        out.resolve("content/README.md").delete()
    }
}

val bundleContent =
    tasks.register<BundleContent>("bundleContent") {
        source.set(rootProject.layout.projectDirectory.dir("content"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleContent, BundleContent::outputDir)
        // Each APK needs its own versionCode; the last digit is the CPU type, 0 for the universal APK.
        val base = libs.versions.app.versionCode.get().toInt() * 10
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            output.versionCode.set(base + (abiVersionCodes[abi] ?: 0))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.runtime)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.datastore.preferences.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.koin.test)
}
