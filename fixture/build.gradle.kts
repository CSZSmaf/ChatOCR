plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.chatocr.fixture"
    compileSdk = 35
    defaultConfig { applicationId = "dev.chatocr.fixture"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
}
