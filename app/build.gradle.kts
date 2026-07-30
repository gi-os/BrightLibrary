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
val baseVersionName = "1.2.1-light.1"
val ciRunNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
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
        minSdk = 24
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
            isMinifyEnabled = false
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
        compose = true
    }
}

dependencies {

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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
