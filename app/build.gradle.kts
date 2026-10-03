plugins { id("com.android.application") }

android {
    namespace = "com.engineeringstudyai"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.engineeringstudyai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
