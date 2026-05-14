plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.egron.torchcam"
    compileSdk = 36
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.egron.torchcam"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}
