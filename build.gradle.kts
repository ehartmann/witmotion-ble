plugins {
    id("com.android.library") version "9.4.1" apply false
    // Pins the Kotlin compiler used by AGP's built-in Kotlin support
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}

// Consumers depend on fr.eande.witmotion:ble / :archery; an includeBuild substitutes these projects
subprojects {
    group = "fr.eande.witmotion"
}
