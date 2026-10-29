/**
 * Two things live at the root. Plugin versions are declared once in the
 * block below (`apply false`) so a module applies the plugin without
 * loading a second copy. The other is a dependency-vulnerability scan
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
    // Versions live once, in this block. A subproject that repeats
    // `version "..."` loads a second copy of that plugin; the warning
    // that used to name :app and :audio is the Kotlin plugin doing that.
    // The Android plugin has to be in the same block, and ahead of the
    // Kotlin Android plugin: applying the latter from here without it
    // fails looking for com.android.build.gradle.api.BaseVariant.
    // Subprojects apply these with no version of their own.
    id("com.android.application") version "8.7.3" apply false
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
}

repositories {
    mavenCentral()
}

// A stopgap for a JIT crash, not a tuning choice. On OpenJDK 21 (what cloud
// sessions run), C2, the JVM's optimising compiler, dies with an internal
// error (Type::typerr, ShouldNotReachHere) while compiling Filament.synthesize,
// a 4,556-byte method, and takes the whole test process with it: Gradle
// reports "There were failing tests" with no test named. Reproduced on
// 21.0.12 in :cli and :shell on every run; not seen on CI's Temurin 17, whose
// silent :shell failures were most likely the heap :shell's build file now sizes.
// Excluding that one method from JIT runs it interpreted and leaves every
// other method optimised; CI's jvm-tests was no slower with it. Remove this
// once Filament.synthesize is split into methods C2 can compile; a JDK 21
// test JVM that no longer crashes without the flag is the proof.
//
// CausticInstrument.synthesize (696 bytes) trips the same internal error,
// at type.cpp:1283, on 21.0.12 and 21.0.13. It is intermittent where
// Filament's was every run, but each time it kills a test JVM: it stopped
// #690's full local run, one of #723's, and the serial baseline #703 was
// measured against, each worked round by hand. Running it interpreted costs
// nothing measurable: its own classes and StarterKitsTest, which renders
// CAUSTIC instruments, took 572 s with the exclude and 585 s without, on the
// same machine. Remove it on the same proof as Filament's.
subprojects {
    tasks.withType<Test>().configureEach {
        jvmArgs(
            "-XX:CompileCommand=quiet",
            "-XX:CompileCommand=exclude,com.snipsnap.synth.Filament::synthesize",
            "-XX:CompileCommand=exclude,com.snipsnap.synth.CausticInstrument::synthesize",
        )
    }
}

// One test JVM per CPU, so a module's test classes share the runner rather
// than queue on one core. CI's jvm-tests spent 87 of its 107 minutes in
// :synth:test (#683's run, 2026-10-26), 410 classes run one after another
// in a single JVM while the runner's second CPU sat mostly idle. A private
// repository's ubuntu-latest has 2 CPUs and 8 GB, so this is two JVMs there.
// Each keeps its module's own maxHeapSize (:synth 2g, :shell 1536m); two
// :synth JVMs peaked at 4.4 GB resident together, measured on 2 pinned CPUs
// locally. The same measurement put :synth at about 1.75 times faster: each
// class runs a little slower beside another, but two run at once.
//
// Classes are split across JVMs, never the methods of one class, so a test
// class's own shared state is untouched. Tests that write files write them
// under temporary directories of their own; the two that write elsewhere
// (GoldenSnapshotTest's missing goldens, UatSimTest's transcript) write a
// file no other class reads. The timing asserts guard against hangs and
// runaway cancellations, and every one passed with two JVMs on two CPUs.
subprojects {
    tasks.withType<Test>().configureEach {
        maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }
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
