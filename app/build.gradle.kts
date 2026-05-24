import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

fun String.asBuildConfigString(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

val healthApiBaseUrl = localProperties.getProperty(
    "HEALTH_API_BASE_URL",
    "https://web-production-94f63.up.railway.app",
)
val healthApiToken = localProperties.getProperty("HEALTH_API_TOKEN", "")

android {
    namespace = "com.sanhaengii.wearhealthsender"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sanhaengii.wearhealthsender"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "HEALTH_API_BASE_URL", healthApiBaseUrl.asBuildConfigString())
        buildConfigField("String", "HEALTH_API_TOKEN", healthApiToken.asBuildConfigString())
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
