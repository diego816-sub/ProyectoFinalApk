import java.util.Properties

plugins { id("com.android.application") }

val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val mapsKey = secrets.getProperty("MAPS_API_KEY", "").trim()
require(mapsKey.isEmpty() || mapsKey.matches(Regex("[A-Za-z0-9_-]+"))) {
    "MAPS_API_KEY contiene caracteres no válidos. Revisa secrets.properties."
}

android {
    namespace = "com.diego.pasoapaso"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.diego.pasoapaso"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.10"
        resValue("string", "maps_api_key", mapsKey)
    }
    buildFeatures { viewBinding = true; resValues = true }
    // Los tres idiomas deben estar disponibles también si se distribuye un App Bundle.
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-maps:20.0.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    testImplementation("junit:junit:4.13.2")
}
