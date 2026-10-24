plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

android {
    namespace = "com.robotuth.v3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.robotuth.v3"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val robotApiToken = providers.gradleProperty("ROBOT_API_TOKEN")
            .orElse(localProperties.getProperty("ROBOT_API_TOKEN") ?: "")
            .get()
        buildConfigField("String", "ROBOT_API_TOKEN", "\"$robotApiToken\"")
        // Streaming ASR + wake word kill switches. Default false (production behaviour);
        // a test build flips them WITHOUT touching source: gradlew assembleDebug
        // -PROBOT_STREAMING_ASR=true -PROBOT_WAKE_WORD=true
        buildConfigField(
            "boolean", "ROBOT_STREAMING_ASR",
            providers.gradleProperty("ROBOT_STREAMING_ASR").getOrElse("false")
        )
        buildConfigField(
            "boolean", "ROBOT_WAKE_WORD",
            providers.gradleProperty("ROBOT_WAKE_WORD").getOrElse("false")
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    testOptions {
        // WavFileMic/Mic log via android.util.Log — plain-JVM unit tests don't run
        // Robolectric; return defaults (0) so Log.e calls don't throw.
        unitTests.isReturnDefaultValues = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Face Detection
    implementation("com.google.mlkit:face-detection:16.1.5")

    // Retrofit for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.mockito:mockito-core:5.8.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Jetpack Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Material Icons Extended
    implementation("androidx.compose.material:material-icons-extended")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Compose Material3
    implementation("androidx.compose.material3:material3")

    // Compose Activity
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Compose Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Orionstar AgentOS — register as proper agent app (same path as agent.sample).
    // Without this, opening any third-party Activity makes LiveKit "pip" / dead speaker.
    implementation("com.orionstar.agent:sdk:0.3.3-SNAPSHOT")

    // Orionstar robot CoreService client (com.ainirobot.coreservice.client.*) —
    // visual ability: PersonApi + RobotApi.startFocusFollow. Present in the APK but
    // only *used* when CoreService is installed, so phones/emulators keep working.
    implementation("com.orionstar.robot:core:12.2.1")
}
