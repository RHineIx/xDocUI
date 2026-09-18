import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

// Load local signing properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Fetch credentials
fun getSigningProp(
    propName: String,
    envName: String,
): String = System.getenv(envName) ?: keystoreProperties.getProperty(propName) ?: ""

android {
    namespace = "ix.xdocui"
    compileSdk = 36

    defaultConfig {
        applicationId = "ix.xdocui"
        minSdk = 30
        targetSdk = 36
        versionCode = 20100
        versionName = "2.1.0"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = getSigningProp("storeFile", "SIGNING_KEY_FILE")
            if (storeFilePath.isNotEmpty()) {
                storeFile = file(storeFilePath)
                storePassword = getSigningProp("storePassword", "KEY_STORE_PASSWORD")
                keyAlias = getSigningProp("keyAlias", "KEY_ALIAS")
                keyPassword = getSigningProp("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (getSigningProp("storeFile", "SIGNING_KEY_FILE").isNotEmpty()) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
        getByName("debug") {
            signingConfig =
                if (getSigningProp("storeFile", "SIGNING_KEY_FILE").isNotEmpty()) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        checkReleaseBuilds = false
    }

    sourceSets {
        getByName("main") {
            resources.srcDir("src/main/resources")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
