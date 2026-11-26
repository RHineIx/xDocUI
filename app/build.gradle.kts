import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

fun getSigningProp(
    propName: String,
    envName: String,
): String = System.getenv(envName) ?: keystoreProperties.getProperty(propName) ?: ""

android {
    namespace = "ix.xdocui"
    compileSdk = 37

    defaultConfig {
        applicationId = "ix.xdocui"
        minSdk = 30
        targetSdk = 37
        versionCode = 20300
        versionName = "2.3.0"
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
            ndk {
                abiFilters += "arm64-v8a"
            }
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

}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.luckypray:dexkit:2.3.0")
    testImplementation("junit:junit:4.13.2")
}
