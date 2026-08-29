// Plugin repositories, content-filtered: only Android/AndroidX plugin ids
// ever touch the Google repo, so JVM-only builds (the cloud session, which
// cannot reach dl.google.com) resolve everything from the portal exactly
// as before :app existed.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
include(":cli")
include(":shell")

// :app (Android) joins the build only where an Android SDK exists — the
// desktop session, not the cloud one. Location rules are the standard
// ones: local.properties sdk.dir, then ANDROID_HOME / ANDROID_SDK_ROOT.
// Everything else in this build is pure JVM and never notices.
val localProps = java.util.Properties().apply {
    val f = java.io.File(settingsDir, "local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val androidSdk = sequenceOf(
    localProps.getProperty("sdk.dir"),
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
).filterNotNull().map { java.io.File(it) }.firstOrNull { it.isDirectory }
if (androidSdk != null) {
    include(":app")
} else {
    logger.lifecycle("snipsnap: no Android SDK located - :app not included (JVM modules only)")
}
