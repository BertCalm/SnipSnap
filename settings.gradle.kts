// google() is required from here down: the Android Gradle Plugin's marker
// resolves via the Gradle Plugin Portal, but the actual
// com.android.tools.build:gradle artifact it depends on, plus every androidx
// artifact :app pulls in, only lives on Google's maven.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "snipsnap"

include(":json")
include(":xpm")
include(":audio")
include(":kit")
include(":mpc3")
include(":synth")
include(":loop")
include(":app")
