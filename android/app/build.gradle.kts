import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cchharness.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cchharness.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.property("appVersionCode") as String).toInt()
        versionName = project.property("appVersionName") as String
    }

    // Release builds are signed with a private key kept outside the repo (see the README):
    // storeFile/storePassword/keyAlias/keyPassword in $HARNESS_SIGNING or
    // ~/.config/cc-harness/android-signing.properties. Without it the release build is left unsigned
    // rather than silently falling back to the debug key.
    val signingProps = Properties().apply {
        val f = file(System.getenv("HARNESS_SIGNING") ?: "${System.getProperty("user.home")}/.config/cc-harness/android-signing.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // lintVital would pull the lint toolchain, which the offline build does not have cached.
    lint { checkReleaseBuilds = false }

    testOptions {
        unitTests {
            // Robolectric needs merged resources/manifest to run Compose UI tests on the JVM
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric + Compose need more than the default 512 MB test heap
tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
