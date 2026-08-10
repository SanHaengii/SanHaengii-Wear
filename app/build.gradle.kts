import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    "https://web-production-94f63.up.railway.app"
)
val healthApiToken = localProperties.getProperty("HEALTH_API_TOKEN", "")
val healthApiUserId = localProperties.getProperty("HEALTH_API_USER_ID", "")
// 로컬 Flask 서버 (에뮬레이터: 10.0.2.2, 실기기: 호스트 PC IP)
val trailApiBaseUrl = localProperties.getProperty("TRAIL_API_BASE_URL", "http://10.0.2.2:5001")

// 릴리스 서명 설정 (keystore.properties 에서 로드, 깃에 커밋 금지)
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
if (hasReleaseSigning) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.sanhaengii.app"
    compileSdk = 36 // 🚀 수정: 안정적인 34 버전으로 하향

    defaultConfig {
        applicationId = "com.sanhaengii.app"
        minSdk = 30
        targetSdk = 34 // 🚀 수정
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "HEALTH_API_BASE_URL", healthApiBaseUrl.asBuildConfigString())
        buildConfigField("String", "HEALTH_API_TOKEN", healthApiToken.asBuildConfigString())
        buildConfigField("String", "HEALTH_API_USER_ID", healthApiUserId.asBuildConfigString())
        buildConfigField("String", "TRAIL_API_BASE_URL", trailApiBaseUrl.asBuildConfigString())
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // 🚀 수정: 코틀린 2.0부터는 에러를 내는 composeOptions 블록 삭제됨

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 🚀 수정: dependencies 블록을 맨 아래로 이동
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // 센서 연동 (Health Services)
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // 기본 Compose
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.ui:ui:1.6.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.1")

    // 🚀 수정: 스마트워치(Wear OS) 전용 UI 부품 추가
    implementation("androidx.wear.compose:compose-material:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")

    // 구글 플레이 서비스 (웨어러블 통신용)
    implementation("com.google.android.gms:play-services-wearable:18.1.0")

    // 단위 테스트
    testImplementation("junit:junit:4.13.2")
    // org.json.JSONObject는 Android 스텁에서 RuntimeException을 던지므로 실제 구현체 사용
    testImplementation("org.json:json:20231013")
}
