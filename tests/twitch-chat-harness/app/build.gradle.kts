plugins { id("com.android.application") }
dependencies { implementation("com.caverock:androidsvg-aar:1.4@aar") }
android {
    namespace = "io.github.sabopura.twitchharness"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.sabopura.twitchharness"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    sourceSets["main"].java.srcDir("../../../extensions/twitch/src/main/java")
    sourceSets["main"].java.srcDir("../../../extensions/twitch/ivs-stubs")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
