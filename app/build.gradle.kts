import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// API 키는 local.properties에서 읽는다 (git 제외 파일).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 값 양쪽의 따옴표("...")는 빼고 읽는다
fun key(name: String): String = (localProps.getProperty(name) ?: "").trim().removeSurrounding("\"").trim()
fun intKey(name: String): Int = key(name).toIntOrNull() ?: -1

android {
    namespace = "com.recodate.drive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.recodate.drive"
        minSdk = 28           // Pleos Connect 요구사항
        targetSdk = 34        // Pleos Connect 에뮬레이터 API 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "TMAP_APP_KEY", "\"${key("TMAP_APP_KEY")}\"")
        buildConfigField("String", "DATA_GO_KR_KEY", "\"${key("DATA_GO_KR_KEY")}\"")
        // TMAP 하루 상한을 더 낮추고 싶을 때만 사용 (-1 = 기본값). 공개 무료 수치의 80%보다 높게는 못 올린다.
        buildConfigField("int", "TMAP_DAILY_POI_LIMIT", "${intKey("TMAP_DAILY_POI_LIMIT")}")
        buildConfigField("int", "TMAP_DAILY_ROUTE_LIMIT", "${intKey("TMAP_DAILY_ROUTE_LIMIT")}")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 제출용 APK를 바로 설치할 수 있도록 디버그 키로 서명
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Pleos Connect SDK (신청 없이 사용 가능한 SDK만)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
