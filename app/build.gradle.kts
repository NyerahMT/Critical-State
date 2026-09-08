plugins {
    id("com.android.application")
}

android {
    namespace = "com.nyerahworks.criticalstate"
    compileSdk = 36

    defaultConfig {
        // Use a separate package while diagnosing the G-Mee installer so a hidden
        // or stale package can never conflict with this build.
        applicationId = "com.nyerahworks.criticalstate.compat"
        minSdk = 26
        targetSdk = 33
        versionCode = 3
        versionName = "0.1.2-compat-legacy-signing"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Normal Android 13 accepts modern APK signatures, but some custom
            // package installers are more reliable when the legacy JAR/v1
            // signature is present as well.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
