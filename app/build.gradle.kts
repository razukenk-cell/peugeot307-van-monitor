plugins {
    id("com.android.application")
}

android {
    namespace = "com.razukenk.peugeot307vanmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.razukenk.peugeot307vanmonitor"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.12.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
}
