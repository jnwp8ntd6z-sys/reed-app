import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.metro)
}

val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0")
val olcboxVersionValue = olcboxVersion.get()
val generatedAppInfoDir = layout.buildDirectory.dir("generated/source/olcboxAppInfo/commonMain")

val olcrtcRepoPath = providers.environmentVariable("OLCRTC_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("olcrtc").absolutePath)
val olcrtcRepoDir = rootProject.file(olcrtcRepoPath.get())
val olcrtcAndroidAar = layout.buildDirectory.file("generated/olcrtc/olcrtc.aar")
val olcrtcAndroidAarFile = olcrtcAndroidAar.get().asFile
val olcrtcIosXcframework = layout.buildDirectory.dir("generated/olcrtc/ios/OlcRtcMobile.xcframework")
val olcrtcIosXcframeworkDir = olcrtcIosXcframework.get().asFile

// Go-модуль singbox-mobile (в КОРНЕ репо) собирает СОВМЕЩЁННУЮ XCFramework: olcRTC (пакет
// mobile) + sing-box (пакет singboxmobile) в ОДНУ libgojni. Две отдельные gomobile-
// XCFramework конфликтуют (одинаковый go-рантайм/символы), как и AAR на Android.
val singboxRepoPath = providers.environmentVariable("SINGBOX_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("singbox-mobile").absolutePath)
val singboxRepoDir = rootProject.file(singboxRepoPath.get())

abstract class GenerateAppInfoTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDir = outputDir.get().asFile.resolve("org/olcbox/app")
        packageDir.mkdirs()
        val escapedVersion = version.get().replace("\\", "\\\\").replace("\"", "\\\"")
        packageDir.resolve("GeneratedAppInfo.kt").writeText(
            """
            package org.olcbox.app

            internal object GeneratedAppInfo {
                const val NAME: String = "olcbox"
                const val VERSION: String = "$escapedVersion"
            }
            """.trimIndent() + "\n"
        )
    }
}

val generateAppInfo by tasks.registering(GenerateAppInfoTask::class) {
    version.set(olcboxVersionValue)
    outputDir.set(generatedAppInfoDir)
}

val buildOlcrtcIosXcframework by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds COMBINED olcRTC+sing-box iOS XCFramework (one libgojni) via gomobile."

    inputs.files(
        singboxRepoDir.resolve("mobile.go"),
        singboxRepoDir.resolve("tools.go"),
        singboxRepoDir.resolve("go.mod")
    )
    outputs.dir(olcrtcIosXcframework)

    workingDir = singboxRepoDir

    doFirst {
        delete(olcrtcIosXcframeworkDir)
        olcrtcIosXcframeworkDir.parentFile.mkdirs()
    }

    val goBin = file("${System.getProperty("user.home")}/go/bin").absolutePath
    val path = System.getenv("PATH") ?: ""
    val mergedPath = if (path.contains(goBin)) path else "$goBin:$path"
    // Имя выходной XCFramework = OlcRtcMobile (путь не меняем) → iosApp import OlcRtcMobile
    // получает И Mobile* (olcRTC), И Singboxmobile* (sing-box) из одного модуля.
    commandLine(
        "sh", "-c",
        "set -e; export PATH=\"$mergedPath\"; " +
            "go get github.com/openlibrecommunity/olcrtc@master; " +
            "go get golang.org/x/mobile/bind@latest; " +
            "go mod tidy; " +
            "gomobile bind -target=ios -ldflags \"-s -w -checklinkname=0\" " +
            "-o \"${olcrtcIosXcframeworkDir.absolutePath}\" " +
            "github.com/openlibrecommunity/olcrtc/mobile ."
    )
}

kotlin {
    android {
        namespace = "org.olcbox.app.sharedui"
        compileSdk = 37
        minSdk = 23

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppInfo)
        }

        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.compose.foundation)
            api(libs.compose.resources)
            api(libs.compose.ui.tooling.preview)
            api(libs.compose.material3)

            implementation(compose.materialIconsExtended)
            implementation(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatformSettings)
            implementation(libs.kstore)
            implementation(libs.materialKolor)
            implementation(libs.androidx.datastore.preferences)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activityCompose)
            implementation(libs.androidx.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kstore.file)
            implementation(libs.zxing.core)
            // Единый модуль с olcRTC (mobile) + sing-box (singboxmobile) в одной libgojni.so.
            // Заменяет olcrtc-bin: два gomobile-AAR в одном APK невозможны.
            implementation(project(":sharedUI:reedmobile-bin"))
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kstore.file)
            implementation(libs.jna)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.kstore.file)
        }

        macosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.kstore.file)
        }
    }

    targets
        .withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family.isAppleFamily }
        .configureEach {
            binaries {
                framework {
                    baseName = "SharedUI"
                    isStatic = true
                }
            }
        }
}
