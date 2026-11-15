// SPDX-License-Identifier: GPL-3.0-or-later

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
    android {
        namespace = "app.fit.shared"
        compileSdk = libs.versions.app.compileSdk.get().toInt()
        minSdk = libs.versions.app.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        androidResources {
            enable = true
        }
        withHostTest {}
    }

    // No desktop app: the JVM target runs the fast host tests (Koin verify, DataStore, and later Room
    // migrations and the content check).
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Room's database constructor is an expect/actual object, still in Beta.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Material 3 Expressive (floating toolbar, ButtonGroup, shape-morphing buttons) is still marked experimental.
        all {
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.navigation3.ui)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.datastore.preferences.core)
            implementation(libs.room3.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.snakeyaml.engine.kmp)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.test)
            // MigrationTestHelper is instrumented-only on Android, so migration tests run on the JVM.
            implementation(libs.room3.testing)
            // Compose UI tests and their screenshots run on the JVM, headless (dev machine and CI are
            // Linux x64), instead of on the phone.
            implementation(libs.compose.ui.test)
            implementation(libs.skiko.awt.runtime.linux.x64)
        }
    }
}

compose.resources {
    packageOfResClass = "app.fit.resources"
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    systemProperty("room.schemaDir", "$projectDir/schemas")
    // UI test screenshots (build/, so never committed).
    systemProperty("ui.screenshots.dir", layout.buildDirectory.dir("ui-screenshots").get().asFile.path)
    // Headless: no display on CI, and none needed here.
    systemProperty("java.awt.headless", "true")
    // The content check parses every built-in file.
    systemProperty("content.dir", rootProject.file("content").path)
    inputs.dir(rootProject.file("content")).withPropertyName("content").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    "androidRuntimeClasspath"(libs.compose.ui.tooling)
    add("kspAndroid", libs.room3.compiler)
    add("kspJvm", libs.room3.compiler)
}
