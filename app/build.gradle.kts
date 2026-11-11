import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Environment wins over local.properties, so a one-off `SYNC_URL=... ./gradlew assembleDebug`
// (e.g. pointing at a local mock) really overrides the real values stored in local.properties.
fun buildConfigString(key: String): String {
    val raw = System.getenv(key)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(key) ?: ""
    return "\"" + raw.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "io.github.kopolot.hcsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kopolot.hcsync"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"

        buildConfigField("String", "SYNC_URL", buildConfigString("SYNC_URL"))
        buildConfigField("String", "SYNC_SECRET", buildConfigString("SYNC_SECRET"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload only: release signed with the debug key unless you configure your own.
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.health.connect)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
