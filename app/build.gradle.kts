plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") 
}

android {
    // Updated namespace to match the new module name
    namespace = "ix.xdocui"
    compileSdk = 33

    defaultConfig {
        // Updated applicationId to match the new module name
        applicationId = "ix.xdocui"
        minSdk = 30
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
