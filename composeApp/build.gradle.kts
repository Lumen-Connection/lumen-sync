import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

val appVersionName = appVersionProperties.getProperty("versionName")
    ?: error("versionName is missing from version.properties")

val appVersionCode = appVersionProperties.getProperty("versionCode")
    ?.toIntOrNull()
    ?: error("versionCode must be an integer")

version = appVersionName

kotlin {
    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.compose.foundation)
                implementation(libs.compose.material)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.coroutines.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.serialization.json)
                implementation(libs.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.core)
                implementation(libs.ktor.client.mock)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.service)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.zxing.android)
            }
        }
        val desktopMain by getting {
            resources.srcDir(rootProject.file("LICENSES"))
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation(libs.zxing.core)
            }
        }
    }
}

val releaseKeystore = providers.environmentVariable("LUMEN_SYNC_KEYSTORE").orNull

android {
    namespace = "dev.lumensync.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lumensync.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        ndk.abiFilters += "arm64-v8a"
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("LUMEN_SYNC_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("LUMEN_SYNC_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("LUMEN_SYNC_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.lumensync.app.desktop.MainKt"
        nativeDistributions.appResourcesRootDir.set(project.layout.projectDirectory.dir("src/desktopMain/appResources"))

        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Lumen Sync"
            packageVersion = appVersionName
            description = "Simple peer-to-peer folder synchronization"
            vendor = "Lumen Sync contributors"
            windows {
                menuGroup = "Lumen Sync"
                perUserInstall = true
                shortcut = true
            }
            linux {
                shortcut = true
            }
        }
    }
}

val desktopResourcePlatform = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows"
    System.getProperty("os.name").startsWith("Linux") -> "linux"
    else -> "macos"
}
val desktopSyncthingName = if (desktopResourcePlatform == "windows") "syncthing.exe" else "syncthing"

val buildSyncthingDesktop by tasks.registering(Exec::class) {
    group = "syncthing"
    description = "Build the pinned Syncthing core for the current desktop OS"
    workingDir(rootProject.projectDir)
    commandLine("go", "run", rootProject.file("tools/build_syncthing.go"), "desktop")
    environment(
        "GOTOOLCHAIN",
        "go${providers.fileContents(rootProject.layout.projectDirectory.file("GO_VERSION")).asText.get().trim()}+auto",
    )
    inputs.files(
        rootProject.file("SYNCTHING_VERSION"),
        rootProject.file("GO_VERSION"),
        rootProject.file("tools/build_syncthing.go"),
    )
    outputs.file(
        project.layout.projectDirectory.file(
            "src/desktopMain/appResources/$desktopResourcePlatform/$desktopSyncthingName",
        ),
    )
}

val buildSyncthingAndroid by tasks.registering(Exec::class) {
    group = "syncthing"
    description = "Cross-compile the pinned Syncthing core for Android arm64"
    workingDir(rootProject.projectDir)
    commandLine("go", "run", rootProject.file("tools/build_syncthing.go"), "android")
    environment(
        "GOTOOLCHAIN",
        "go${providers.fileContents(rootProject.layout.projectDirectory.file("GO_VERSION")).asText.get().trim()}+auto",
    )
    inputs.files(
        rootProject.file("SYNCTHING_VERSION"),
        rootProject.file("GO_VERSION"),
        rootProject.file("tools/build_syncthing.go"),
    )
    outputs.file(project.layout.projectDirectory.file("src/androidMain/jniLibs/arm64-v8a/libsyncthing.so"))
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(buildSyncthingDesktop)
}

tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(buildSyncthingAndroid)
}

tasks.matching {
    it.name.startsWith("package") && (it.name.endsWith("Msi") || it.name.endsWith("Deb"))
}.configureEach {
    dependsOn(buildSyncthingDesktop)
}
