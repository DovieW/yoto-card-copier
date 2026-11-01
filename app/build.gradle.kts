plugins { id("com.android.application") }
android {
    namespace = "dev.dovie.yotocopy"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.dovie.yotocopy"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
