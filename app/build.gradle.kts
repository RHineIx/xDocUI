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

// Release tags are the source of truth; ordinary local/CI builds use a default.
val releaseTag = providers.gradleProperty("releaseTag")
    .orElse(providers.environmentVariable("GITHUB_REF_TYPE").flatMap { type ->
        if (type == "tag") providers.environmentVariable("GITHUB_REF_NAME")
        else providers.gradleProperty("xdocuiLocalTag").orElse("v2.3.0")
    })
    .orElse(providers.gradleProperty("xdocuiLocalTag"))
    .orElse("v2.3.0")
    .get()
val versionMatch = requireNotNull(
    Regex("""v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(-rc)?""").matchEntire(releaseTag)
) { "Invalid release tag: $releaseTag (expected vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-rc)" }
val (majorText, minorText, patchText) = versionMatch.destructured
val major = majorText.toLongOrNull() ?: error("Version major is too large")
val minor = minorText.toLong()
val patch = patchText.toLong()
require(major <= 210000L) { "Version major exceeds Android versionCode range" }
val computedVersionCode = major * 10000L + minor * 100L + patch
require(computedVersionCode in 1L..2100000000L) { "Invalid Android versionCode: $computedVersionCode" }
val computedVersionName = "$majorText.$minorText.$patchText"

android {
    namespace = "ix.xdocui"
    compileSdk = 37

    defaultConfig {
        applicationId = "ix.xdocui"
        minSdk = 30
        targetSdk = 37
        versionCode = computedVersionCode.toInt()
        versionName = computedVersionName
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
