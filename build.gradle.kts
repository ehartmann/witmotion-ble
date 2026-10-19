import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.ivy.IvyPublication

plugins {
    id("com.android.library") version "9.4.1" apply false
    // Pins the Kotlin compiler used by AGP's built-in Kotlin support
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}

// Released as AARs attached to a GitHub Release (.github/workflows/release.yml). Consumers read
// them through an Ivy repository on the release download URLs, so build/release-repo uses flat
// file names: <module>-<version>.aar, .module (Gradle metadata), .xml (Ivy descriptor), -sources.jar.
subprojects {
    group = "fr.eande.witmotion"
    version = providers.gradleProperty("libVersion").getOrElse("0.0.0-SNAPSHOT")

    pluginManager.withPlugin("com.android.library") {
        apply(plugin = "ivy-publish")
        extensions.configure<LibraryExtension> {
            publishing { singleVariant("release") { withSourcesJar() } }
        }
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications { create<IvyPublication>("release") { from(components["release"]) } }
                repositories {
                    ivy {
                        name = "release"
                        url = uri(rootProject.layout.buildDirectory.dir("release-repo"))
                        patternLayout {
                            artifact("[module]-[revision](-[classifier]).[ext]")
                            ivy("[module]-[revision].[ext]")
                        }
                    }
                }
            }
        }
    }
}
