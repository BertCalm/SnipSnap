// The Android Gradle Plugin resolves from Google's Maven, not the plugin
// portal — :app cannot apply com.android.application without this.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "snipsnap"

include(":json")
include(":xpm")
include(":audio")
include(":kit")
include(":mpc3")
include(":synth")
include(":cli")
include(":shell")
include(":app")
