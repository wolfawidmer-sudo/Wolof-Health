import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi") version "1.43.0"
}

android {
    namespace = "ch.widmer.airview"
    compileSdk = 36
    defaultConfig {
        applicationId = "ch.widmer.airview"
        minSdk = 28
        targetSdk = 35
        versionCode = 17
        versionName = "2.1"
        buildConfigField("String", "UPDATE_URL", "\"https://raw.githubusercontent.com/wolfawidmer-sudo/wolof-health/main/updates/version.json\"")
    }
    // Fester Release-Schlüssel – liegt NICHT im Repo (siehe README). Ohne ihn baut kein Release.
    val keyDir = file(System.getenv("WOLOF_SIGNING_DIR") ?: "/home/claude/signing")
    val keyProps = Properties().apply { file("$keyDir/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = file("$keyDir/${keyProps["storeFile"]}")
                storePassword = keyProps["storePassword"] as String
                keyAlias = keyProps["keyAlias"] as String
                keyPassword = keyProps["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Hintergrund-Unschärfe für die Glas-Leiste (Android 12+, darunter durchscheinende Fläche)
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Glance zieht alte stdlib-jdk7/8 nach; auf die verwendete Kotlin-Version festlegen
configurations.all {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.20",
    )
}
