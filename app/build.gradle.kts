plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.gatar.recipedealer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gatar.recipedealer"
        minSdk = 34
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release key outside both repos (`../signing/`, see CLAUDE.md "Release APK"); without it the release stays unsigned.
    val signingDir = rootProject.file("../signing")
    val releaseKeystore = File(signingDir, "recipedealer-release.jks")
    val releasePassword = File(signingDir, "store-password.txt")
    signingConfigs {
        if (releaseKeystore.exists() && releasePassword.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storeType = "pkcs12"
                storePassword = releasePassword.readText().trim()
                keyAlias = "recipedealer"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        // test builds keep the last release's number: the suffix tells them apart in the system's app info
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // No R8: optimizing only androidx / kotlin(x) made kotlin.text classes inaccessible to the app's own code
            // (IllegalAccessError at start, 2026-10-27). The release runs the same code as the tested debug build.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // The instrumented extractor test compares the real WebView against the JVM goldens.
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
}

// Golden tests: `./gradlew :app:testDebugUnitTest -PupdateGolden` rewrites the expected files instead of comparing.
tasks.withType<Test>().configureEach {
    systemProperty("updateGolden", project.hasProperty("updateGolden").toString())
    systemProperty("projectDir", rootProject.projectDir.absolutePath)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json.schema.validator)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
