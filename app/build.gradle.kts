import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Single source of truth for the version. The CI workflow greps
// `baseVersionName` out of this file to name the APK artifacts, so keep it as a
// plain string literal.
val baseVersionName = "1.18.0-light.1"
val ciRunNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

android {
    // Both the namespace and the applicationId are com.lightfastread, so this
    // shares no identifier of any kind with upstream FastRead - not the package,
    // not the R class, and not the auto-generated permission and provider
    // authorities that AndroidX derives from them. Installing this can never
    // collide with an existing FastRead build.
    namespace = "com.lightfastread"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lightfastread"
        // Was 24, upstream FastRead's floor, kept so the fork still installed on an old test
        // device. light-common is minSdk 29 and the manifest merger refuses to link below it —
        // and overriding that would be a lie, since the library reaches for APIs (SystemFonts
        // among them) that do not exist on 24. The phone this is built for is API 34, so 29 is
        // the honest number.
        minSdk = 29
        // LightOS ships Android 14 (API 34); the light-sdk emulator profile is
        // API 34 as well. Compiling against 36 is fine, but there is no reason
        // to opt into 35/36 behaviour changes the device will never see.
        targetSdk = 34

        // CI stamps every build with the workflow run number so each pushed APK
        // has a strictly higher versionCode than the last and installs over it
        // with `adb install -r`. Local builds stay at 1 - if you have a CI build
        // on the device already, a local install needs `adb install -r -d` to
        // allow the version downgrade.
        versionCode = ciRunNumber ?: 1
        versionName = ciRunNumber?.let { "$baseVersionName-b$it" } ?: baseVersionName

        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // When a keystore is configured, sign debug with it too. Android
            // identifies an app by (packageName, signing certificate), so a
            // debug build signed with the throwaway ~/.android/debug.keystore
            // cannot replace an installed release build - and CI runners
            // regenerate that throwaway key on every single job.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {

    // The shared Light library: the wheel, the hardware keys, shake-to-report and the
    // LightSync backup provider. MIT, consumed by this GPL-3.0 fork — which is fine in that
    // direction; nothing from this repo goes back the other way.
    implementation("com.gios:light-common:1.2.1")
    // What makes the AAR's baseline profile actually get applied: below API 31 nothing on the
    // device reads a profile on its own, so without this the profile ships and is ignored.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)

    // Reading one QR code: the four Calibre settings without typing them. CameraX for the preview
    // and the analysis loop, ML Kit's *bundled* barcode model for the decode - the Play-services
    // variant downloads its model on first use, and LightOS has no Play Services to download it
    // from, so it would wait forever on the one phone this app is for.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
