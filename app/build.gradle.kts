plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Постоянный ключ подписи: путь и пароль приходят из переменных окружения
// (в GitHub Actions — из секретов). Без них сборка подписывается debug-ключом.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val hasPersonalKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "io.github.barsicc.swapgps"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.barsicc.swapgps"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

        // Только 64-битные телефоны (все современные) — карта весит много, APK заметно меньше
        ndk { abiFilters += listOf("arm64-v8a") }

        // Ключ Яндекс MapKit подставляется при сборке из секрета YANDEX_MAPKIT_KEY
        buildConfigField("String", "MAPKIT_KEY", "\"" + (System.getenv("YANDEX_MAPKIT_KEY") ?: "") + "\"")
    }

    signingConfigs {
        create("personal") {
            if (hasPersonalKey) {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "personal"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = if (hasPersonalKey) signingConfigs.getByName("personal") else signingConfigs.getByName("debug")
        }
        release {
            // Без сжатия кода: надёжнее с нативной картой, а проверить на телефоне мы можем не всё
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasPersonalKey) signingConfigs.getByName("personal") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Яндекс MapKit: карта, поиск, подсказки адресов, маршруты
    implementation("com.yandex.android:maps.mobile:4.45.0-full")

    testImplementation("junit:junit:4.13.2")
}
