plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.vxs.frostsoulxdsp"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.vxs.frostsoulxdsp"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.ui:ui:1.10.0")
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha23")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
}

// Tiny SDK metadata assets enable exporting the installed engine independently of the lab.
val engineSdkAssets = layout.buildDirectory.dir("generated/engineSdkAssets")
val prepareEngineSdkAssets by tasks.registering(Sync::class) {
    into(engineSdkAssets.map { it.dir("engine-sdk") })
    from("src/main/cpp/engine/include") { into("include") }
    from("src/main/cpp/engine/CMakeLists.txt")
    val importedLicense = file("src/main/cpp/engine/LICENSE.md")
    val sourceLicense = file("src/main/cpp/engine/third_party/steamaudio_sdk/LICENSE.md")
    from(if (importedLicense.exists()) importedLicense else if (sourceLicense.exists()) sourceLicense
        else rootProject.file("engine-bundle/third_party/steamaudio_sdk/LICENSE.md"))
}
android.sourceSets.getByName("main").assets.srcDir(engineSdkAssets)
tasks.named("preBuild").configure { dependsOn(prepareEngineSdkAssets) }
