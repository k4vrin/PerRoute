import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import groovy.json.JsonSlurper

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "app.perroute"
version = "3.0.0"

val stageNativeResources = tasks.register<Sync>("stageNativeResources") {
    into(layout.buildDirectory.dir("native-resources"))
    from(layout.projectDirectory.dir("tools/xray")) { into("common/xray") }
    from(layout.projectDirectory.file("README.md")) { into("common") }
    from(layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) { into("common") }
    from(layout.projectDirectory.file("LICENSE")) { into("common") }
    from(zipTree(layout.projectDirectory.file("lib/jna-5.17.0.jar"))) {
        include("com/sun/jna/win32-x86-64/jnidispatch.dll")
        eachFile { path = "common/jnidispatch.dll" }
        includeEmptyDirs = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(files("lib/flatlaf-3.7.2.jar"))
    implementation(files("lib/jna-5.17.0.jar"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    implementation("com.github.sarxos:webcam-capture:0.3.12")
}

compose.desktop {
    application {
        mainClass = "app.perroute.compose.ComposeMainKt"
        jvmArgs += listOf("-XX:+UseSerialGC", "-Xms64m", "-Xmn32m", "-Xmx1g", "-XX:MinHeapFreeRatio=20", "-XX:MaxHeapFreeRatio=40")
        nativeDistributions {
            modules("java.net.http", "java.prefs", "java.naming", "jdk.naming.dns","jdk.crypto.ec", "jdk.unsupported")
            appResourcesRootDir.set(layout.buildDirectory.dir("native-resources"))
            targetFormats(TargetFormat.Exe)
            packageName = "PerRoute"
            packageVersion = "3.0.0"
            description = "Modern per-application proxy client"
            vendor = "PerRoute"
            macOS {
                bundleID = "app.perroute.spike"
            }
            windows {
                iconFile.set(project.file("src/main/resources/icons/perroute.ico"))
                console = false
                perUserInstall = true
                dirChooser = true
                menu = true
                shortcut = true
                menuGroup = "PerRoute"
                upgradeUuid = "3d39ae88-05df-4c36-ae6a-268c0ca6ed5a"
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(stageNativeResources)
}

tasks.matching { it.name in setOf("createDistributable", "packageExe") }.configureEach {
    dependsOn(stageNativeResources)
    inputs.dir(layout.buildDirectory.dir("native-resources"))
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        if (System.getProperty("os.name") == "Mac OS X") {
            // Compose 1.8.2 copies app resources without preserving Unix execute bits.
            // Restore Darwin engine permissions in the local image before it is packaged for delivery.
            val resources = layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app/Contents/app/resources/xray").get().asFile
            for (folder in listOf("macos-arm64", "macos-x64")) {
                val engine = resources.resolve("$folder/xray")
                if (engine.isFile) check(engine.setExecutable(true, false)) { "Cannot make packaged Xray executable: $engine" }
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}

// Renders every screen off-screen with demonstration data: baseline, narrow, 150% scaling, both themes.
// Pass scene names with -Pscenes=a,b to render only those.
tasks.register<JavaExec>("renderUiSnapshots") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.UiSnapshotsKt")
    args(layout.buildDirectory.dir("ui-snapshots").get().asFile.absolutePath)
    (project.findProperty("scenes") as String?)?.split(',')?.filter { it.isNotBlank() }?.let { args(it) }
}

tasks.register<JavaExec>("runAllTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.AllTests")
    enableAssertions = true
    // Shifts every PerRoute port so the real-core integration test can run next to an installed PerRoute.
    systemProperty("perroute.portOffset", "20000")
}

// Runs platform selection, diagnostics and fake-backed maintenance/local/system regressions.
tasks.register<JavaExec>("runPlatformBoundaryTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.PlatformBoundaryTests")
    enableAssertions = true
    systemProperty("perroute.portOffset", "20000")
}

// Keep the v3 suites reachable even when a legacy Windows-path test stops runAllTests on another host.
listOf("Domain" to "domain", "Controller" to "controller").forEach { (name, suite) ->
    tasks.register<JavaExec>("runV3${name}Tests") {
        dependsOn(tasks.named("testClasses"))
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("app.perroute.V3RegressionTests")
        args(suite)
        enableAssertions = true
        systemProperty("perroute.portOffset", "20000")
    }
}

// Isolated native feasibility targets. This does not package, sign, install or activate an extension.
val macosSpikeOutput = layout.buildDirectory.dir("macos-spike")
val buildMacosExtensionSpike = tasks.register<Exec>("buildMacosExtensionSpike") {
    inputs.dir(layout.projectDirectory.dir("native/macos-spike"))
    outputs.dir(macosSpikeOutput)
    doFirst {
        check(System.getProperty("os.name").lowercase().let { it == "mac os x" || it == "darwin" }) {
            "The native extension spike requires macOS with Xcode"
        }
    }
    commandLine("xcodebuild", "-project", "native/macos-spike/PerRouteMacosSpike.xcodeproj",
        "-scheme", "PerRouteMacosSpike", "-configuration", "Debug",
        "-derivedDataPath", macosSpikeOutput.get().asFile.absolutePath,
        "CODE_SIGNING_ALLOWED=NO", "build")
}

tasks.register<JavaExec>("inspectMacosExtensionSpike") {
    dependsOn(tasks.named("classes"), buildMacosExtensionSpike)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.spike.MacExtensionSpike")
    args(macosSpikeOutput.get().file("Build/Products/Debug/libPerRouteExtensionBridge.dylib").asFile.absolutePath)
}

tasks.register<JavaExec>("runMacosExtensionSpikeTests") {
    dependsOn(tasks.named("testClasses"), buildMacosExtensionSpike)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosExtensionSpikeTests")
    args(macosSpikeOutput.get().file("Build/Products/Debug/libPerRouteExtensionBridge.dylib").asFile.absolutePath)
    enableAssertions = true
}

// Copy the real Compose app image; never modify the base image or install into Applications here.
val macosSpikeApp = layout.buildDirectory.dir("macos-spike-app/PerRoute.app")
val stageMacosExtensionSpikeApp = tasks.register<Sync>("stageMacosExtensionSpikeApp") {
    dependsOn(tasks.named("createDistributable"), buildMacosExtensionSpike)
    into(macosSpikeApp)
    from(layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app"))
    from(macosSpikeOutput.map { it.dir("Build/Products/Debug") }) {
        include("libPerRouteExtensionBridge.dylib")
        into("Contents/Frameworks")
    }
    from(macosSpikeOutput.map { it.dir("Build/Products/Debug/app.perroute.spike.transparent-proxy.systemextension") }) {
        into("Contents/Library/SystemExtensions/app.perroute.spike.transparent-proxy.systemextension")
    }
    doLast {
        check(macosSpikeApp.get().file("Contents/MacOS/PerRoute").asFile.isFile) {
            "Compose application launcher is missing"
        }
        check(macosSpikeApp.get().file("Contents/Library/SystemExtensions/app.perroute.spike.transparent-proxy.systemextension/Contents/MacOS/PerRouteSpikeExtension").asFile.isFile) {
            "Embedded system extension executable is missing"
        }
    }
}

tasks.register<Exec>("inspectMacosExtensionSpikeApp") {
    dependsOn(stageMacosExtensionSpikeApp)
    val output = ByteArrayOutputStream()
    standardOutput = output
    commandLine(macosSpikeApp.get().file("Contents/MacOS/PerRoute").asFile.absolutePath,
        "--extension-spike", macosSpikeApp.get().file("Contents/Frameworks/libPerRouteExtensionBridge.dylib").asFile.absolutePath)
    doLast {
        val snapshot = JsonSlurper().parseText(output.toString(Charsets.UTF_8)) as Map<*, *>
        check(snapshot["bundleIdentifier"] == "app.perroute.spike") { "Probe did not identify the Compose container: $snapshot" }
        check(file(snapshot["bundlePath"].toString()).canonicalFile == macosSpikeApp.get().asFile.canonicalFile) {
            "Probe ran in a different app container: $snapshot"
        }
        check(snapshot["state"] == "idle" && snapshot["requestPending"] == false) { "Inspection must not submit an OS request" }
        val evidence = layout.buildDirectory.file("macos-spike-app/inspection.json").get().asFile
        evidence.writeText(output.toString(Charsets.UTF_8))
        println("Compose container bridge inspection passed: ${snapshot["bundleIdentifier"]}")
    }
}

tasks.register<JavaExec>("runXrayRuntimeTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.XrayRuntimeTests")
    enableAssertions = true
}

// Explicit local-only traffic test with a developer-supplied official Darwin engine.
tasks.register<JavaExec>("runMacosEngineSmoke") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosEngineSmokeTests")
    systemProperty("perroute.portOffset", "20000")
    args(layout.projectDirectory.dir("tools/xray").asFile.absolutePath)
    enableAssertions = true
}

tasks.register<JavaExec>("runMacosDesktopTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosDesktopTests")
    args(layout.projectDirectory.dir("tools/xray").asFile.absolutePath)
    systemProperty("perroute.portOffset", "20000")
    enableAssertions = true
}
