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
        versionCode = 2
        versionName = "0.1.1-compat"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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
