/**
 * The one thing that lives at the root: a dependency-vulnerability scan
 * over every module's resolved dependencies (transitive included), not
 * just the direct ones a build.gradle.kts happens to name.
 *
 * Applied here rather than in each module's own build script — the
 * plugin's own multi-project guidance is to apply it once at the root and
 * let `dependencyCheckAggregate` walk every subproject that's part of the
 * current build (so :app's Android-only dependencies are covered when an
 * SDK is present, and simply absent from the scan otherwise, same as
 * everywhere else in this build - see settings.gradle.kts).
 *
 * Run it with `./gradlew dependencyCheckAggregate`. It downloads and
 * caches the NVD's CVE feed under the Gradle user home the first time,
 * which is slow and, without an API key, rate-limited hard enough to
 * sometimes fail outright - set NVD_API_KEY (env var) or pass
 * -PnvdApiKey=... to use one; nvd.nist.gov/developers/request-an-api-key
 * issues them for free. Neither is committed here.
 */
plugins {
    id("org.owasp.dependencycheck") version "13.0.0"
}

repositories {
    mavenCentral()
}

dependencyCheck {
    // High and Critical (CVSS 7.0+) fail the build; Medium/Low land in the
    // report without blocking anything. A scan that fails on every Low
    // finding trains everyone to ignore it.
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    // Documented, reasoned exclusions only - see the file's own header.
    suppressionFiles = listOf("$rootDir/config/dependency-check-suppressions.xml")

    // A GitHub Actions secret that was never configured resolves to an
    // empty string, not an absent one - takeIf keeps that indistinguishable
    // from truly unset, rather than handing the plugin an empty key that
    // would fail differently (and less clearly) than no key at all.
    nvd.apiKey = (project.findProperty("nvdApiKey") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }

    analyzers.apply {
        // This build has no .NET, Node, Ruby, Cocoapods, Swift package or
        // Go module anywhere in it - only the JVM/Kotlin modules and
        // :app's Gradle/Android dependencies. Narrowing to what can
        // actually be true here cuts scan time and false-positive surface;
        // a module in a language this project doesn't use is a door the
        // scanner otherwise still knocks on.
        assemblyEnabled = false
        nodeEnabled = false
        nodeAuditEnabled = false
        nuspecEnabled = false
        nugetconfEnabled = false
        cocoapodsEnabled = false
        swiftPackageResolvedEnabled = false
        golangDepEnabled = false
        golangModEnabled = false
        rubygemsEnabled = false
    }
}
