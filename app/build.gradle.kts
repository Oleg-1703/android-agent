plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.oleg.androidagent"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.oleg.androidagent"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
