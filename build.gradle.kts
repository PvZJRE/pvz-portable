import io.github.pvzjre.build.BuildInfo
import io.github.pvzjre.build.Lwjgl
import io.github.pvzjre.build.McPackage
import io.github.pvzjre.build.configureRunTask
import io.github.pvzjre.build.PvzAssetIndex
import io.github.pvzjre.build.pvzHarness
import io.github.pvzjre.build.pvzLaunchDefaults
import io.github.pvzjre.build.pvzTestConventions
import io.github.pvzjre.build.registerLibopenmptNatives
import io.github.pvzjre.build.registerMcVersionJson
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.application.tasks.CreateStartScripts

// This script declares *what* this project builds: its identity, what the distribution contains, the
// tasks a release and the drivers run through, and the Minecraft-style package. The reasoning behind
// those - the libopenmpt pin and its staging, how a driver task is launched, how the version json is
// written, what the packaged launch scripts add to Gradle's own - is documented where the code is,
// in `buildSrc/` under `io.github.pvzjre.build`. AGENTS.md describes the result (zip layout,
// `lib/native/`, `mods/`).

plugins {
    id("java-library")
    application
    `maven-publish`
}

group = "io.github.pvzjre"
// The game's own version, independent of the upstream C++ project's tags and of the sibling
// repositories' own versions (the API, the toolchain and every mod carry theirs): it is what a release
// reads (see the `printVersion` task), the prefix of the two release packages (see the naming
// convention in the Distribution section), the version the game provider reports to Fabric Loader
// (`PvzGameProvider.DEFAULT_GAME_VERSION`, which this line and that constant both state) and what a
// mod names in `pvz("io.github.pvzjre:pvz-portable:<version>")` to build against it.
version = "1.2.5"

// The version is stated twice: by the line above, and by the game provider the loader asks for it
// (PvzGameProvider.DEFAULT_GAME_VERSION, which the loader reports as the built-in `pvz-portable`
// mod's version). This check is what keeps the two from drifting: a build that is packaged under one
// version and reports another is exactly the kind of mistake that survives a release otherwise.
val checkGameVersion = tasks.register("checkGameVersion") {
    group = "verification"
    description = "Checks that PvzGameProvider.DEFAULT_GAME_VERSION states this build's version."
    val providerSource = layout.projectDirectory.file(
        "src/main/java/io/github/pvzjre/provider/PvzGameProvider.java"
    )
    val expected = project.version.toString()
    inputs.file(providerSource)
    inputs.property("version", expected)
    doLast {
        val stated = Regex("""DEFAULT_GAME_VERSION\s*=\s*"([^"]+)"""")
            .find(providerSource.asFile.readText())?.groupValues?.get(1)
        if (stated != expected) {
            throw IllegalStateException(
                "PvzGameProvider.DEFAULT_GAME_VERSION is \"$stated\" but this build is $expected:" +
                    " the two have to state the same version (see the `version` line and the constant)."
            )
        }
    }
}
tasks.named("check") { dependsOn(checkGameVersion) }

// The artifact's own name, matching the Maven artifactId (`io.github.pvzjre:pvz-portable`), the
// distribution and the release asset: `build/libs/pvz-portable-<version>.jar`, `lib/pvz-portable-<version>.jar`
// inside the zip, `~/.m2/repository/io/github/pvzjre/pvz-portable/<version>/pvz-portable-<version>.jar`.
base {
    archivesName = "pvz-portable"
}

// The player-supplied global cursor (光标13.cur, an untracked build input at the project root)
// rides in the game jar as /pvzp/cursor.cur - the same treatment the bundled window icon gets -
// and the game adopts it as its global cursor (see SexyAppBase.applyGlobalCursorFile). Builds
// without the file ship the stock cursors.
tasks.named<Jar>("jar") {
    from(files("resources/光标13.cur")) {
        into("pvzp")
        rename { "cursor.cur" }
    }
}

// Java 25 is what the game's class files are - the toolchain, `--release`, the distribution's start
// script and the Minecraft-style package's `javaVersion` all state that one version.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(McPackage.JAVA_MAJOR_VERSION)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = McPackage.JAVA_MAJOR_VERSION
}

// ------------------------------------------------------------------------------------------------
// Dependencies
// ------------------------------------------------------------------------------------------------

val lwjglVersion = libs.versions.lwjgl.get()

// The mods the loader end-to-end test (FabricLoaderEndToEndTest) finds in a real mods/ directory:
// this workspace's API as one mod and the example mod. Both are resolved from mavenLocal and copied
// into build/testmods by [testModsJar] below. Deliberately NOT on the test classpath: mods load from
// the mods directory exactly like they do in a real installation.
val modsForTest by configurations.creating

// The API release this project's tests are written against. The project is standalone: the API
// arrives as a mavenLocal coordinate, never as a file of a sibling checkout, so changing the API
// means publishing it again first ((cd ../fabric-api && ./gradlew publishToMavenLocal)).
val apiVersion = "1.2.2"

// The API is a *mod* of the game directory, and never a dependency of this project: fabric-api
// depends on this game in turn (its event types are typed against the game's classes), so the two
// projects cannot depend on each other. It is consumed in two shapes, and they are not
// interchangeable - the aggregator, `io.github.pvzjre:fabric-api`, is the whole API as **one mod**
// (its five feature modules sit inside it as `META-INF/jars/*.jar`, so it is what a `mods/` directory
// and the loader end-to-end test take), and the five feature modules themselves are what `javac`
// needs, because a class inside a nested jar is not on a compile class path.
val apiModules = listOf("api-base", "api-events", "api-plant", "api-resource", "api-zombie")

dependencies {
    // The game's own output goes through the SLF4J API (see Common.LOGGER_NAME), so this is the one
    // dependency of the game's own code: the implementation is deliberately absent, because the
    // provider publishes the workspace's one binding (Log4j, through log4j-slf4j2-impl) and its
    // log4j2.xml as launch libraries - adding either here would make a second binding and a second
    // configuration possible. `api` so that the version is the one this build compiles and publishes
    // with, which is also the provider's (2.0.20).
    api(libs.slf4j.api)

    api(platform(libs.lwjgl.bom))
    Lwjgl.artifacts.forEach { module ->
        api("org.lwjgl:$module")
        // The natives of every platform this build ships, not the build machine's: `runtimeOnly` is
        // what makes them the launch class path, i.e. what puts them in `lib/` of the distribution
        // *and* on the class path of the `bin/pvz-portable` script (which Gradle generates from it),
        // so the unpacked zip runs the game on the platform it is unpacked on. The six platforms and
        // their LWJGL classifiers are `Lwjgl.distributionNativePlatforms` (buildSrc) - written out,
        // not derived from the build machine, because the release zip has to be the same zip wherever
        // it is built.
        Lwjgl.distributionNativePlatforms.forEach { classifier ->
            runtimeOnly(Lwjgl.natives(lwjglVersion, module, classifier))
        }
    }

    // The mod loader is the REAL Fabric Loader, provided by the launcher and never a dependency of
    // the game's own code: the application entry point is Fabric Loader's KnotClient, which finds
    // this game through the game provider of this project (src/main/java/io/github/pvzjre/provider,
    // the GameProvider SPI implementation, merged into this jar - it used to be the fabric-provider
    // artifact) and loads the game directory's mods/ before invoking the game's main class. The
    // launch dependencies below are the library set of the loader's own manifest (fabric-loader-<v>.json):
    // the loader, Mixin and ASM, plus the Log4j stack the workspace logs through.
    compileOnly(libs.fabric.loader)
    runtimeOnly(libs.fabric.loader)
    runtimeOnly(libs.sponge.mixin)
    runtimeOnly(libs.asm)
    runtimeOnly(libs.asm.analysis)
    runtimeOnly(libs.asm.commons)
    runtimeOnly(libs.asm.tree)
    runtimeOnly(libs.asm.util)
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)

    // The game's main sources are Fabric-free; its test fixtures are mods, so they compile against
    // the loader (ModInitializer) and query it (FabricLoader facade) when they run.
    testImplementation(libs.fabric.loader)
    // The log4j2.xml config test reconfigures the live LoggerContext, which is core types
    // (the backend itself is already on the test runtime class path via the launch stack).
    testImplementation(libs.log4j.core)

    // The fixtures (`src/test/java/.../contentmodfixture/`) import the API's public types, so their
    // *compilation* gets the API's five feature modules from mavenLocal - compile-only, and
    // deliberately not `testRuntimeOnly`: nothing of the test class path may carry the API, because
    // the game has to find it exactly where a player's installation keeps it, in the mods directory
    // the loader reads (`testModsJar` below).
    apiModules.forEach { testCompileOnly("io.github.pvzjre:$it:$apiVersion") }

    // The API as one mod and the example mod: the mods the loader end-to-end test loads from the real
    // mods/ directory `testModsJar` builds below. The aggregate jar is the whole API - its five
    // feature modules are nested inside it - so its POM's compile dependencies on those five modules
    // must not drag five further jars next to it: a `mods/` directory takes the aggregate and nothing
    // else of the API.
    modsForTest("io.github.pvzjre:fabric-api:$apiVersion") { isTransitive = false }
    modsForTest("io.github.pvzjre:pvz-example-mod:1.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ------------------------------------------------------------------------------------------------
// Distribution
//
// The two release artifacts, and the one naming convention they follow:
//
//     pvz-portable-<version>-offline.zip / .tar   the application plugin's distribution: `bin/`,
//                                                 `lib/` and `lib/native/` - everything the game
//                                                 needs to run, with nothing left to download
//     pvz-portable-<version>-online.zip           the Minecraft-style version package: a version
//                                                 folder a launcher fills from the libraries its
//                                                 json names (below, "Minecraft-client-style")
//
// Both share the one prefix - the application's own name, `base.archivesName` plus the project
// version - and the suffix says whether the package is self-contained (`-offline`, the game
// distribution) or needs a launcher and a network (`-online`, the version package). Only the file
// names carry it: the *directory inside* each zip keeps the name its consumer needs
// (`pvz-portable-<version>/` for the offline package, the Minecraft version id
// `PvZ-Portable-<version>/` for the online one).
val distributionName = "${base.archivesName.get()}-${project.version}"

// The zip is `bin/` (start scripts, generated from the runtime class path) plus:
//   lib/          the game jar and every runtime dependency it needs to start (the provider with the
//                 real Fabric Loader, Mixin and ASM; the SLF4J API plus the provider's Log4j stack
//                 and log4j2.xml; the LWJGL modules with every platform's natives)
//   lib/native/   the packaged libopenmpt DLLs: `<platform>/` + licence + manifest, the layout the
//                 game's own native lookup probes (see `libopenmptNatives`)
//
// Nothing else, and in particular no `mods/` directory and no mod jar: a mod is something the *player*
// installs, and Fabric Loader reads `mods/` from the game directory it is launched on.
// ------------------------------------------------------------------------------------------------

// Downloads the pinned official libopenmpt binaries (sha256-verified) and stages `lib/native/`.
// Windows only: upstream publishes no macOS or Linux binaries (lib.openmpt.org/libopenmpt/download
// points every other operating system at its "distribution packages"), so those platforms keep the
// system library lookup. The pin, the download and the staging are `Libopenmpt` in buildSrc.
val libopenmptNatives = registerLibopenmptNatives()

distributions {
    getByName("main") {
        contents {
            from(libopenmptNatives) {
                into("lib/native")
            }
        }
    }
}

application {
    // Fabric Loader's launcher, exactly as `run` uses it: it finds this game through the provider's
    // GameProvider and loads the game directory's mods/ before calling the game's main class, so the
    // distribution's `bin/pvz-portable` script is that launch without Gradle.
    mainClass = McPackage.MAIN_CLASS
    // The dist directory (and the script names) inside the zip: `pvz-portable-<version>/bin/pvz-portable`.
    applicationName = "pvz-portable"
    applicationDefaultJvmArgs = listOf(
        // LWJGL uses JNI; JDK 25 warns (and future JDKs refuse) unless native access is enabled.
        "--enable-native-access=ALL-UNNAMED",
        // This port is the only game provider that can be present, so skip Fabric Loader's Minecraft
        // detection pass over the class path (the `run` task passes the same switch).
        "-Dfabric.skipMcProvider=true",
    )
    // -XstartOnFirstThread is deliberately *not* here: it is a *launcher* option only macOS's `java`
    // consumes (ProcessPlatformOption in libjli), so a JVM on Linux or Windows refuses the launch
    // ("Unrecognized option") and baking it in would break the released zip there. The zip is one zip
    // for every platform: the switch goes into the generated unix script behind a platform test (see
    // `startScripts` below).
}

// The release contract: the game distribution a release attaches is the application plugin's own zip
// (`distZip`, not a hand-rolled archive). The file name says which kind of package it is, while the
// directory *inside* the archive stays the application's own `<name>-<version>` - the path README
// documents (`pvz-portable-<version>/bin/pvz-portable`) - because a distribution's root directory
// otherwise follows its archive file name; `installDist`'s `build/install/pvz-portable/` is that same
// tree without the root directory, and keeps it.
// The build identity the game logs (BuildInfo): the version, the commit count and the commit's date,
// written into a resource the game reads at startup. A build number of 0 and an empty commit date are
// what an un-injected build reports, which is what the first version of this port did.
val buildInfo = tasks.register<WriteProperties>("buildInfo") {
    destinationFile = layout.buildDirectory.file("build-info/" + BuildInfo.RESOURCE)
    comment = "Written by the buildInfo task; read by io.github.pvzjre.portable.BuildInfo."
    property(BuildInfo.Keys.VERSION, project.version.toString())
    property(BuildInfo.Keys.BUILD_NUMBER, BuildInfo.buildNumber(project))
    property(BuildInfo.Keys.COMMIT_DATE, BuildInfo.commitDate(project))
}

// The resource is part of the jar (and of the test runtime class path), so both a packaged game and a
// development run state the same build identity.
tasks.named<ProcessResources>("processResources") {
    from(buildInfo)
}

// The 14 bytes of the packages' empty asset index (PvzAssetIndex): the build writes them, so the
// repository does not carry a copy of a constant - and the packages always ship the content their own
// json declares by sha1 and size. The json's url names the same bytes under the version's own tag.
val emptyAssetIndex = tasks.register("emptyAssetIndex") {
    // Named exactly as the release asset the json's url names (PvzAssetIndex.assetName), so a release
    // uploads this task's output unchanged.
    val output = layout.buildDirectory.file("asset-index/" + PvzAssetIndex.assetName(project.version.toString()))
    outputs.file(output)
    doLast { PvzAssetIndex.write(output.get().asFile) }
}

// Both packages also carry the empty asset index a launcher's pre-launch check wants (see
// PvzAssetIndex): with the file at `assets/indexes/<id>.json` of an installation - which is what
// copying `<package>/launcher/` over the game directory does - the check is satisfied locally and the
// launch never touches the network for it.
tasks.named<Zip>("distZip") {
    archiveFileName = "$distributionName-offline.zip"
    into(distributionName)
    from(emptyAssetIndex) {
        into("launcher/assets/indexes")
        rename { PvzAssetIndex.fileName(project.version.toString()) }
    }
}

tasks.named<Tar>("distTar") {
    archiveFileName = "$distributionName-offline.tar"
    into(distributionName)
}

// Gradle names the start script *files* after the project directory (`PvZ-Portable`), so both are
// renamed to the application name: the distribution ships `bin/pvz-portable` and
// `bin/pvz-portable.bat`, like the jar and the release asset. Beyond that, every generated script
// sets its own game directory (the unpacked root next to `bin/`, where Fabric Loader reads mods/)
// and the unix one adds `-XstartOnFirstThread` on macOS only - `pvzLaunchDefaults()` (buildSrc) has
// that text and why it cannot come from `applicationDefaultJvmArgs`.
tasks.named<CreateStartScripts>("startScripts") {
    applicationName = "pvz-portable"
    val scriptsDir = layout.buildDirectory.dir("scripts")
    unixScriptFile.set(scriptsDir.map { it.file("pvz-portable") })
    windowsScriptFile.set(scriptsDir.map { it.file("pvz-portable.bat") })
    pvzLaunchDefaults()
}

// ------------------------------------------------------------------------------------------------
// Tests
// ------------------------------------------------------------------------------------------------

val testModsDir = layout.buildDirectory.dir("testmods")

// The mods the loader end-to-end test finds in a real `mods/` directory: the API's aggregate jar and
// the example mod, both resolved from mavenLocal (`modsForTest` above). One `Sync`, because
// `build/testmods` is Gradle's own directory: two syncs into it would prune each other's jars. The
// test reads the directory through the `pvz.test.modsDir` system property the test task sets below.
tasks.register<Sync>("testModsJar") {
    group = "verification"
    description = "Installs the mods the loader end-to-end test loads into build/testmods."
    from(configurations.named("modsForTest"))
    into(testModsDir)
}

// JUnit 5, the mods the loader end-to-end test loads from `build/testmods`
// (`pvz.test.modsDir` - a real mods/ directory, because the test class path carries no API), the
// release zip the distribution test asserts (`pvz.test.distribution`), and the headless-ish SDL
// drivers: `pvzTestConventions` in buildSrc.
pvzTestConventions(testModsDir, layout.projectDirectory.dir("run"))

// ------------------------------------------------------------------------------------------------
// Development tasks
//
// The game as a developer runs it: `run` is the distribution's launch without Gradle, and the drivers
// below are the same launch with a harness as the main class (`io.github.pvzjre.portable.test.*`).
// They all start in `run/`, a real game directory whose `mods/` the loader reads - this build
// installs nothing into it - and `pvzHarness` (buildSrc) is what they launch with: switch forwarding
// (`-Ppvz.…` first, then `-D`), the game directory property, the dummy audio device and the macOS
// first-thread switch.
// ------------------------------------------------------------------------------------------------

// The development game directory, and the one place this script points at it: `run`, whose `mods/`
// holds what the developer (or the player) put there by hand.
val devRunDir = layout.projectDirectory.dir("run")

// `run` carries the packaged native libraries at the same relative path the distribution uses
// (`run/lib/native/<os>-<arch>/`), so a dev run resolves libopenmpt exactly like an unpacked release
// does: from the packaged directory when it holds one for this platform, from the system library
// otherwise. `run` is the one game directory the driver tasks share.
val libopenmptRunNatives = tasks.register<Sync>("libopenmptRunNatives") {
    group = "distribution"
    description = "Stages the packaged native libopenmpt libraries in the development game directory."
    from(libopenmptNatives)
    into(devRunDir.dir("lib/native"))
}

// Visual smoke check: boots the game with the real video driver and writes what the window actually
// shows into build/window-dump/ (see io.github.pvzjre.portable.test.WindowDump). Useful because
// the headless test run cannot see the window: this is how the "window is black" class of bug gets
// caught.
pvzHarness(
    name = "dumpWindow",
    mainClass = "io.github.pvzjre.portable.test.WindowDump",
    description = "Runs the game with the real video driver and screenshots its window.",
    switches = listOf("pvz.renderer", "pvz.window.dumpDir"),
)

// Frames of one rendering scene: jumps into the level the scenario needs and dumps the window (see
// io.github.pvzjre.portable.test.SceneDump). Scenario: graveRise | fume | packet | zomboni.
// The zomboni scenario is the only one that plays the level intro at the game's own pace (it needs
// the mowers the intro places and the board panned back to x=0, see SceneDump.playIntro).
pvzHarness(
    name = "sceneDump",
    mainClass = "io.github.pvzjre.portable.test.SceneDump",
    description = "Dumps the window for one SceneDump scenario (-Ppvz.dump.scenario=...).",
    switches = listOf("pvz.dump.scenario", "pvz.renderer"),
)

// Frames of the level-award seed-packet pickup (the packet that grows to 2x while the level fades to
// white): dumps the transition plus the renderer's scaled-draw probe (see
// io.github.pvzjre.portable.test.AwardPickupDump).
pvzHarness(
    name = "zoomDump",
    mainClass = "io.github.pvzjre.portable.test.AwardPickupDump",
    description = "Dumps the award seed-packet pickup transition and probes scaled-draw filtering.",
    switches = listOf("pvz.renderer", "pvz.zoom.dumpDir", "pvz.zoom.resDir", "pvz.zoom.logClicks"),
)

// End-to-end playthrough: plays Adventure mode through the real window with synthetic clicks pushed
// into SDL's event queue (see io.github.pvzjre.portable.test.AdventurePlaythrough).
pvzHarness(
    name = "playthrough",
    mainClass = "io.github.pvzjre.portable.test.AdventurePlaythrough",
    description = "Plays Adventure mode end to end with real clicks and reports per-level results.",
    switches = listOf(
        "pvz.renderer", "pvz.play.firstLevel", "pvz.play.lastLevel",
        "pvz.play.tickBudget", "pvz.play.fast", "pvz.play.logClicks",
        "pvz.play.firstMode", "pvz.play.lastMode", "pvz.play.fromMenu",
    ),
)

// Frame-rate benchmark: runs the real loop and reports per-second fps, draw/blit time and GC activity
// (see io.github.pvzjre.portable.test.FrameBench). Pass -Ppvz.bench.jfr=<file> for a JFR profile.
pvzHarness(
    name = "bench",
    mainClass = "io.github.pvzjre.portable.test.FrameBench",
    description = "Runs the game loop and reports frame-rate and GC statistics.",
    switches = listOf(
        "pvz.renderer", "pvz.bench.seconds", "pvz.bench.jfr", "pvz.bench.level",
        "pvz.bench.zombies", "pvz.bench.cpuFrames", "pvz.bench.iterations",
    ),
)

// Extreme-load frame benchmark: builds a synthetic late-game board (a full lawn of shooters, a
// 100/300/600 zombie flood, survival-endless waves past 40, an effect storm) and reports per-frame
// update/draw/present percentiles, drops against the 100 Hz budget, allocation/GC and object peaks
// (see io.github.pvzjre.portable.test.StressBench).
// Scenes: title | empty | peashooter | gatling | zombies | endless | effects.
// Modes:  frame (harness drives the frame; per-phase timings) | loop (the game's own loop, sampled).
pvzHarness(
    name = "stressBench",
    mainClass = "io.github.pvzjre.portable.test.StressBench",
    description = "Runs the extreme-load frame benchmark (-Ppvz.stress.scene=... -Ppvz.renderer=...).",
    switches = listOf(
        "pvz.renderer", "pvz.stress.scene", "pvz.stress.mode", "pvz.stress.seconds",
        "pvz.stress.frames", "pvz.stress.repeats", "pvz.stress.warmup", "pvz.stress.zombies",
        "pvz.stress.level", "pvz.stress.endlessStage", "pvz.stress.endlessCountDown",
        "pvz.stress.zombieCap", "pvz.stress.loopSeconds", "pvz.stress.maxWallSeconds",
        "pvz.stress.presentEvery", "pvz.stress.countDraws", "pvz.stress.effects.suns",
        "pvz.stress.effects.coins", "pvz.stress.effects.cherryBombs", "pvz.stress.replant",
        "pvz.stress.jfr",
    ),
)

// Optional: copy assets from a local resource directory into the run dir for manual testing.
tasks.register<Copy>("syncDevResources") {
    val resDir = providers.gradleProperty("pvzResDir").orNull
    if (resDir != null) {
        from(resDir) {
            include("main.pak")
            include("properties/**")
        }
        into(layout.projectDirectory.dir("run"))
    }
}

// `run`: the game on the application's own class path (the game, the provider and their runtime
// dependencies - no mod is on it), in `run/`, with the packaged native libraries staged next to it.
// Its JVM arguments - the game directory, the provider switch, `fabric.development`, LWJGL's native
// access and macOS's first-thread switch - are `configureRunTask` (buildSrc).
configureRunTask(devRunDir.asFile, libopenmptRunNatives)

// ------------------------------------------------------------------------------------------------
// Release
// ------------------------------------------------------------------------------------------------

// Prints the project version on its own line: a release reads it with `./gradlew -q printVersion` and
// checks it against the tag before creating the release (README.md, "Releases").
tasks.register("printVersion") {
    group = "help"
    description = "Prints the project version, the value a release names the asset after."
    val projectVersion = project.version.toString()
    doLast { println(projectVersion) }
}

// The game jar describes itself: the loader reports the game's version through the game provider
// (io.github.pvzjre.provider, part of this project), so this manifest is what a human or a tool reading the artifact - the jar inside
// the release zip, or the one published as io.github.pvzjre:pvz-portable:<version> - sees.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to "pvz-portable",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "io.github.pvzjre"
            artifactId = "pvz-portable"
            // The project version: one value for the jar, the POM and the distribution, so a
            // consumer of `io.github.pvzjre:pvz-portable:<version>` gets exactly the release zip's
            // game jar.
            version = project.version.toString()
            pom {
                name.set("pvz-portable")
                description.set("Java port of PvZ-Portable (engine + game) using LWJGL/SDL3")
                licenses {
                    license {
                        name.set("LGPL-3.0-or-later")
                        url.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Minecraft-client-style version package
//
// One zip whose content is the version folder a launcher reads out of `versions/`:
//
//     PvZ-Portable-<version>/PvZ-Portable-<version>.jar    the game, with the provider bundled - it is
//                                                         this workspace's own launcher glue and has
//                                                         no download address of its own
//     PvZ-Portable-<version>/PvZ-Portable-<version>.json   main class, launch arguments and every
//                                                         dependency by name and download URL
//
// No dependency file: a launcher reads the json and downloads the libraries it names. `install*` puts
// the version into an installation's `versions/`, the mods into `mods/`, and the package's own empty
// asset index into `assets/indexes/`. The rules, the launch arguments and how a library entry is
// derived from a resolved artifact are `McPackage`/`McVersionJsonTask` in buildSrc.
// ------------------------------------------------------------------------------------------------

/** The version id of the Minecraft-style package: the folder, the json and the jar all carry it. */
val mcVersionId = "PvZ-Portable-${project.version}"

/** The id of the package's own empty asset index, `pvz-portable-<project version>` (see below). */
val mcAssetIndexId = PvzAssetIndex.id(project.version.toString())

// The version jar: the game jar under the version id's name. The game provider (the GameProvider SPI
// implementation that the loader discovers) is part of the game's own sources, so the jar is complete
// on its own: the launch class path needs nothing that has no download address, and nothing rides
// inside this jar that is not in the library list of the json either.
val mcVersionJar = tasks.register<Jar>("mcVersionJar") {
    group = "distribution"
    description = "Builds the version jar of the Minecraft-style package (the game, provider included)."
    dependsOn(tasks.named("jar"))
    archiveFileName = "$mcVersionId.jar"
    destinationDirectory = layout.buildDirectory.dir("mcversion/$mcVersionId")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(zipTree(tasks.named<Jar>("jar").flatMap { it.archiveFile })) {
        exclude("META-INF/MANIFEST.MF")
    }
    manifest {
        attributes(
            "Implementation-Title" to "pvz-portable",
            "Implementation-Version" to project.version.toString(),
        )
    }
    // The jar carries a Main-Class and the library class path, so a plain `java -jar` (and a
    // double click) starts the game the same way a launcher does - no launcher argument is lost,
    // every launcher keeps passing its own -cp, where a manifest is inert. The class path lists
    // the json's libraries exactly as installMcVersion lays them out (../../libraries/<path>,
    // relative to the version folder the jar sits in). The class path resolves at execution time
    // (the same snapshotting discipline McVersionJsonTask applies). The JVM-level arguments a
    // launcher passes cannot live in a manifest: Launcher-Agent-Class runs the premain agent
    // (VersionJarAgent) that rewrites java.class.path to the manifest's list before the loader
    // splits it (in -jar mode the property names the jar alone and the game's classes would
    // split across the two class loaders), the provider resolves the game directory from the
    // jar's own location as the last resort, and the loader picks this port's provider even
    // without -Dfabric.skipMcProvider (this port is the only one whose locate() succeeds here).
    doFirst {
        manifest.attributes(
            "Main-Class" to McPackage.MAIN_CLASS,
            "Launcher-Agent-Class" to "io.github.pvzjre.provider.VersionJarAgent",
            // Deliberately NOT the manifest's own `Class-Path` attribute: the JVM expands it onto
            // the application class loader of EVERY launch shape (launchers that pass -cp too),
            // and the libraries then being visible to the game's class loader breaks Fabric
            // Loader's class-loader split (the game's entrypoint interfaces end up defined
            // twice). The agent reads this custom attribute instead and wires the libraries the
            // way a launcher's -cp does, resolving each entry against the candidate roots of the
            // two layouts it serves (the installed version folder, the jpackage app image).
            "X-Class-Path" to McPackage.libraries(configurations.runtimeClasspath.get())
                .filter { it.os == null || (it.os == "windows" && it.arch == "x86_64") }
                .joinToString(" ") { it.path },
        )
    }
}

val mcVersionJson = registerMcVersionJson(mcVersionId, mcAssetIndexId, project.version.toString())

/** The launcher-shaped zip: `<version>/<version>.jar` + `<version>/<version>.json` + natives. */
val mcVersionZip = tasks.register<Zip>("mcVersionZip") {
    group = "distribution"
    description = "Packages the Minecraft-client-style version zip (version folder: jar + json)."
    dependsOn(mcVersionJar, mcVersionJson)
    archiveFileName = "$distributionName-online.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    into(mcVersionId)
    from(mcVersionJar)
    from(mcVersionJson)
    from(emptyAssetIndex) {
        into("launcher/assets/indexes")
        rename { PvzAssetIndex.fileName(project.version.toString()) }
    }
    // The libopenmpt binaries a launcher installation needs for the MO3 music. The offline
    // distribution carries the same set in its own `lib/native/`; without them the version folder
    // has no packaged copy and every launcher launch logs "Music disabled".
    from(libopenmptNatives) {
        into("lib/native")
    }
}

// The exe version's deployment branch: a single-file Windows launcher compiled by the .NET
// Framework's own csc.exe (present on every Windows since 7 - no third-party tool, no download).
// The launcher embeds the version jar as a resource ("integrate all non-external-library files"),
// keeps the libraries external beside it, finds the locally installed Java 25 (JAVA_HOME, PATH,
// the registry, the common Adoptium/Microsoft/Zulu directories, version-verified) and - when
// missing or older than 25 - shows a minimal prompt that opens the Adoptium download page. No
// bundled runtime, no app/ folder: saves and mods live next to the exe.
val installExeDist = tasks.register("installExeDist") {
    group = "distribution"
    description = "Builds the exe version (single-file launcher + external libraries) into dist/."
    dependsOn(mcVersionJar, libopenmptNatives)
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }

    doLast {
        val staging = layout.buildDirectory.dir("exe-dist").get().asFile
        staging.deleteRecursively()
        staging.mkdirs()

        // the package libraries, in the maven layout; the game (and its provider) ride in the
        // version jar itself, which the launcher embeds
        for (artifact in configurations.named("runtimeClasspath").get().incoming.artifacts.resolvedArtifacts.get()) {
            val identifier = artifact.id.componentIdentifier
            if (identifier !is org.gradle.api.artifacts.component.ModuleComponentIdentifier) continue
            if (identifier.group == "io.github.pvzjre") continue
            if (artifact.file.name.contains("natives-") && !artifact.file.name.contains("natives-windows")) continue
            val relative = "${identifier.group.replace('.', '/')}/${identifier.module}/${identifier.version}/${artifact.file.name}"
            val target = File(staging, "libraries/$relative")
            target.parentFile.mkdirs()
            artifact.file.copyTo(target, overwrite = true)
        }

        // the libopenmpt binaries of this machine's architecture
        for (dir in libopenmptNatives.get().outputs.files) {
            val windows = File(dir, "windows-x64")
            if (windows.isDirectory) {
                windows.copyRecursively(File(staging, "lib/native/windows-x64"), overwrite = true)
            }
        }

        // the asset package is the player's, never this repository's - point at it
        File(staging, "把 main.pak 放到本目录.txt").writeText(
            "游戏启动还需要资产包 main.pak：从现有的 PvZ-Portable 版本目录（或游戏安装目录）"
                    + "把 main.pak 复制到本文件夹即可。\r\n",
            Charsets.UTF_8,
        )

        // the player-supplied Win32 resources: 版本信息1.rc (the version info) + 图标组1.ico (the
        // icon), bound through the Windows SDK's rc.exe into one .res the csc compile picks up.
        // Skipped with a warning when either the files or the SDK are absent.
        val compileArgs = mutableListOf(
            "/nologo", "/target:winexe", "/optimize+",
            "/r:System.Windows.Forms.dll", "/r:System.Drawing.dll",
        )
        val rcSource = File(layout.projectDirectory.asFile, "resources/版本信息1.rc")
        val iconSource = File(layout.projectDirectory.asFile, "resources/图标组1.ico")
        if (rcSource.isFile && iconSource.isFile) {
            fun locateRcExe(): File? {
                val kits = File("C:\\Program Files (x86)\\Windows Kits\\10\\bin")
                if (!kits.isDirectory) {
                    return null
                }
                return kits.listFiles { f -> f.isDirectory }!!
                    .sortedByDescending { it.name }
                    .firstNotNullOfOrNull { kit ->
                        val rc = File(kit, "x64/rc.exe")
                        if (rc.isFile) rc else null
                    }
            }
            val rcExe = locateRcExe()
            if (rcExe != null) {
                iconSource.copyTo(File(staging, "appicon.ico"), overwrite = true)
                // the .rc is ASCII content in a UTF-16 container; rewritten as plain ASCII with
                // the icon bound at id 1 (the icon Explorer and the taskbar pick up)
                val versionInfo = rcSource.readText(Charsets.UTF_16LE)
                    // the UTF-16 BOM survives the decode and poisons rc.exe's parse of the
                    // VERSIONINFO directive (RC2135 file not found) - strip it
                val rcFile = File(staging, "versioninfo.rc")
                rcFile.writeText(versionInfo.removePrefix("\uFEFF")
                        + "\r\n1 ICON \"appicon.ico\"\r\n", Charsets.US_ASCII)
                val resource = File(staging, "versioninfo.res")
                val rc = ProcessBuilder(
                        rcExe.absolutePath, "/nologo", "/fo", resource.absolutePath, rcFile.absolutePath)
                    .redirectErrorStream(true).start()
                val rcOutput = rc.inputStream.bufferedReader().readText()
                if (rc.waitFor() != 0 || !resource.isFile) {
                    logger.warn("installExeDist: rc.exe failed, the exe ships without version info/icon:\n{}", rcOutput)
                } else {
                    compileArgs += "/win32res:" + resource.absolutePath
                    logger.lifecycle("installExeDist: version info + icon bound (versioninfo.res)")
                }
            } else {
                logger.warn("installExeDist: rc.exe (Windows SDK) not found, the exe ships without version info/icon")
            }
        } else {
            logger.warn("installExeDist: 版本信息1.rc / 图标组1.ico not found in the project root, the exe ships without version info/icon")
        }

        // the launcher: the .NET Framework's own compiler (no download, present on every Windows)
        val cscCandidates = listOf(
            File(System.getenv("WINDIR") ?: "C:\\Windows", "Microsoft.NET/Framework64/v4.0.30319/csc.exe"),
            File(System.getenv("WINDIR") ?: "C:\\Windows", "Microsoft.NET/Framework/v4.0.30319/csc.exe"),
        )
        val csc = cscCandidates.firstOrNull { it.isFile }
        require(csc != null) { "csc.exe not found (the .NET Framework is required to compile the launcher)" }

        val launcherSource = File(staging, "PvZLauncher.cs").apply {
            // the BOM is what makes csc read the source as UTF-8 (without it: the system
            // codepage, which mangles non-ASCII text)
            writeText("﻿" + layout.projectDirectory
                    .file("buildSrc/src/main/resources/io/github/pvzjre/build/PvZLauncher.cs")
                    .asFile.readText(Charsets.UTF_8), Charsets.UTF_8)
        }
        val versionJar = mcVersionJar.get().archiveFile.get().asFile

        val compile = ProcessBuilder(
            csc.absolutePath,
            *compileArgs.toTypedArray(),
            "/res:" + versionJar.absolutePath + ",game.jar",
            "/out:" + File(staging, "PlantsVsZombies.exe").absolutePath,
            launcherSource.absolutePath,
        ).redirectErrorStream(true).start()
        val compileOutput = compile.inputStream.bufferedReader().readText()
        if (compile.waitFor() != 0) {
            throw GradleException("csc failed:\n$compileOutput")
        }
        // the build intermediates (the launcher source and the compiled win32 resources) do not
        // ship - only the exe, the libraries and the natives do
        launcherSource.delete()
        File(staging, "appicon.ico").delete()
        File(staging, "versioninfo.rc").delete()
        File(staging, "versioninfo.res").delete()

        val dist = layout.projectDirectory.dir("dist").asFile
        dist.deleteRecursively()
        staging.copyRecursively(dist, overwrite = true)

        logger.lifecycle(
            "installExeDist: dist/ deployed ({}; no main.pak - drop it next to the exe to play)",
            versionJar.name,
        )
    }
}

// The everything-at-once deployment branch: the MC version folder (installMcVersion) and the exe
// version in the project's dist/ (installExeDist). The global cursor needs no deployment: the
// player's 光标13.cur rides inside the game jar itself (/pvzp/cursor.cur, see the jar task).
val deployAll = tasks.register("deployAll") {
    group = "distribution"
    description = "Deploys everything: the MC version folder and the exe dist."
    dependsOn(installMcVersion, installExeDist)
}

/** The Minecraft installation this package is installed into / launched from. */
val mcGameDir = providers.gradleProperty("mcGameDir").orNull
    ?: "${System.getProperty("user.home")}/Library/Application Support/minecraft"

/** The mods copied into the installation's `mods/`: what the development `run/` directory loads. */
val mcModsDir = providers.gradleProperty("mcModsDir").orNull
    ?: layout.projectDirectory.dir("run/mods").asFile.absolutePath

val mcVersionsDir = File(mcGameDir, "versions")
val mcModsTarget = File(mcGameDir, "mods")

/** The mods of the installation: every mod jar of [mcModsDir]. */
val installMcMods = tasks.register<Copy>("installMcMods") {
    group = "distribution"
    description = "Copies the mods of -PmcModsDir (default run/mods) into the installation's mods/."
    from(mcModsDir) {
        include("*.jar")
    }
    into(mcModsTarget)
}

/**
 * Installs the version package into a Minecraft installation: the version folder under `versions/`
 * (what this task's zip carries), through [installMcMods] the mods under `mods/`, and the package's
 * own empty asset index under `assets/indexes/` whose checksum and size the version json declares
 * (see [PvzAssetIndex]). All three are what a launcher reads, so a launcher started on that
 * installation lists and can launch the version with the mods loaded - and its pre-launch repair
 * finds nothing to download, instead of going after vanilla's 146 MB `legacy` index.
 */
/** What a reinstall of the version folder keeps: the saves (the current `saves/` and the
 *  pre-rename `savedata/`, which the game migrates to `saves/` on its next launch), the settings
 *  registry (config/), the mods and the assets the player placed. */
val REINSTALL_KEEP = listOf("saves", "savedata", "config", "mods", "main.pak", "properties")

// Where a reinstall parks the keep between the move-aside and the move-back. A fixed path (not the
// task's temporaryDir) because it must survive a FAILED run: the keep holds the player's saves and
// assets, and the restore has to be able to pick it up no matter how the previous run ended.
val reinstallKeepDir = layout.buildDirectory.dir("tmp/reinstall-keep")

/** Moves whatever the keep dir holds back under the version folder. Idempotent: a no-op when the
 *  keep is absent (every restore deletes it), safe when the target already exists. */
fun restoreReinstallKeep() {
    val keep = reinstallKeepDir.get().asFile
    if (!keep.isDirectory) {
        return
    }
    val folder = File(mcVersionsDir, mcVersionId)
    folder.mkdirs()
    for (name in REINSTALL_KEEP) {
        val kept = File(keep, name)
        if (kept.exists()) {
            val target = File(folder, name)
            when {
                // The version folder already has this entry (the game recreated it while a failed
                // run's keep was parked): merge the keep over it rather than discarding it.
                // A file that cannot be overwritten (the running game holds its mods jars open)
                // is skipped: the destination copy is the live one, which is exactly the content
                // the keep would restore.
                target.isDirectory && kept.isDirectory ->
                    kept.copyRecursively(target, overwrite = true,
                            onError = { _, _ -> OnErrorAction.SKIP })
                // A rename inside one drive just moves it; the copy is the fallback for a
                // cross-device target.
                !target.exists() && kept.renameTo(target) -> {}
                else ->
                    if (kept.isDirectory) {
                        kept.copyRecursively(target, overwrite = true)
                    } else {
                        kept.copyTo(target, overwrite = true)
                    }
            }
        }
    }
    keep.deleteRecursively()
}

val restoreReinstallKeepTask = tasks.register("restoreReinstallKeep") {
    group = "distribution"
    description = "Moves the reinstall keep (saves/mods/assets) back under the version folder."
    doLast {
        restoreReinstallKeep()
    }
}

val installMcVersion = tasks.register<Copy>("installMcVersion") {
    group = "distribution"
    description = "Installs the Minecraft-style version into -PmcGameDir (default: the macOS install)."
    dependsOn(mcVersionZip, installMcMods)
    from({ zipTree(mcVersionZip.get().archiveFile.get().asFile) })
    into(mcVersionsDir)
    // The index this package owns, next to the installation's other indexes: it carries our version
    // id, so it never collides with one of vanilla's.
    val assetIndex = PvzAssetIndex.inGameDir(File(mcGameDir), project.version.toString())
    outputs.file(assetIndex)
    // The restore runs no matter how this task ends: a copy that fails halfway (the game running,
    // a locked library) must never orphan the keep — it holds the player's saves and assets.
    finalizedBy(restoreReinstallKeepTask)
    doFirst {
        // The version folder is replaced as a whole, so a stale library or an old jar cannot
        // survive - except of what the game and the player put there: the saves, the mods and
        // the game assets the player placed (`main.pak`, a loose `properties/`) are moved aside
        // here and the finalizer moves them back (the zip carries none of them). A keep that is
        // already here belongs to a run that died before its restore could run: it is put back
        // FIRST, never deleted - deleting it took a player's saves with it once.
        restoreReinstallKeep()
        val folder = File(mcVersionsDir, mcVersionId)
        val keep = reinstallKeepDir.get().asFile
        if (folder.isDirectory) {
            for (name in REINSTALL_KEEP) {
                val source = File(folder, name)
                if (source.exists()) {
                    val kept = File(keep, name)
                    kept.parentFile.mkdirs()
                    if (!source.renameTo(kept)) {
                        if (source.isDirectory) {
                            source.copyRecursively(kept, overwrite = true)
                        } else {
                            source.copyTo(kept, overwrite = true)
                        }
                    }
                }
            }
        }
        folder.deleteRecursively()
    }
    doLast {
        PvzAssetIndex.install(File(mcGameDir), project.version.toString())
        logger.lifecycle(
            "installMcVersion: {} installed into {} (mods from {}, empty asset index {})",
            mcVersionId, mcVersionsDir, mcModsDir, assetIndex,
        )
    }
}
