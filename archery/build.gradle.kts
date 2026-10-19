plugins {
    id("com.android.library")
}

android {
    namespace = "fr.eande.witmotion.archery"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's SDK 36 runtime reaches into jdk.internal.access (ApplicationSharedMemory)
        unitTests.all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":ble"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("org.robolectric:robolectric:4.17")
}
