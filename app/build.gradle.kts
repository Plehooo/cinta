import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val supabaseProperties = Properties().apply {
    val configFile = rootProject.file("supabase.properties")
    if (configFile.exists()) configFile.inputStream().use { load(it) }
}
fun buildConfigString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val configuredSupabaseUrl = System.getenv("SUPABASE_URL")
    ?: (project.findProperty("SUPABASE_URL") as? String)
    ?: supabaseProperties.getProperty("SUPABASE_URL", "")
val configuredSupabaseKey = System.getenv("SUPABASE_PUBLISHABLE_KEY")
    ?: (project.findProperty("SUPABASE_PUBLISHABLE_KEY") as? String)
    ?: supabaseProperties.getProperty("SUPABASE_PUBLISHABLE_KEY", "")

android {
    namespace = "com.plehooo.galericinta"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.plehooo.galericinta"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.2"
        buildConfigField("String", "SUPABASE_URL", buildConfigString(configuredSupabaseUrl))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(configuredSupabaseKey))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// Firebase's Google Services plugin requires the owner's real configuration file.
// Keep the source buildable before the Firebase project is connected.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation(platform("com.google.firebase:firebase-bom:35.0.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
