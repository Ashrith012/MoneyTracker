plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 id("com.google.devtools.ksp")
}
if (file("google-services.json").exists()) { apply(plugin = "com.google.gms.google-services") }
android {
 namespace = "in.moneytrack.app"
 compileSdk = 35
 defaultConfig {
  applicationId = "in.moneytrack.app"
  minSdk = 28
  targetSdk = 35
  versionCode = 1
  versionName = "0.1.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  resValue("string", "api_url", providers.gradleProperty("apiUrl").getOrElse(""))
 }
 providers.gradleProperty("debugKeystore").orNull?.let {
  signingConfigs.getByName("debug").storeFile = file(it)
 }
 buildFeatures { compose = true; buildConfig = false }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(project(":core"))
 implementation(platform("androidx.compose:compose-bom:2025.04.01"))
 implementation("androidx.compose.material3:material3")
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.fragment:fragment-ktx:1.8.6")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
 implementation("androidx.room:room-runtime:2.7.1")
 implementation("androidx.room:room-ktx:2.7.1")
 ksp("androidx.room:room-compiler:2.7.1")
 implementation("net.zetetic:sqlcipher-android:4.10.0")
 implementation("androidx.sqlite:sqlite-ktx:2.5.0")
 implementation("androidx.work:work-runtime-ktx:2.10.1")
 implementation("androidx.biometric:biometric:1.1.0")
 implementation("com.google.android.gms:play-services-auth:22.0.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
 implementation("com.google.firebase:firebase-analytics")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
}
