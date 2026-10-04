import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    application
}

group = "com.mymods.mclauncher"
version = providers.gradleProperty("appVersion").getOrElse("0.2.0")

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

val javafxVersion = "21.0.2"
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()

val javafxPlatform = when {
    osName.startsWith("windows") -> "win"
    osName.startsWith("mac") -> if (osArch.contains("aarch64") || osArch.contains("arm")) "mac-aarch64" else "mac"
    else -> if (osArch.contains("aarch64") || osArch.contains("arm")) "linux-aarch64" else "linux"
}

application {
    mainClass.set("com.mymods.mclauncher.Main")
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

    // No .fxml files or FXMLLoader anywhere: the window is built in code, so only
    // base/graphics/controls are needed.
    listOf("base", "graphics", "controls").forEach { module ->
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:-options"))
}

tasks.named<JavaExec>("run") {
    // The launcher never needs a separate runtime: Minecraft gets its own Java.
    // These flags only keep JavaFX happy when the launcher runs on a bare JDK.
    jvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dprism.order=sw",
    )
}

val packageJdk = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(17)
}
val packageDestination = layout.buildDirectory.dir(
    "package-${project.version}-${System.currentTimeMillis()}")
val packageJpackage = packageJdk.get().metadata.installationPath
        .file(if (osName.startsWith("windows")) "bin/jpackage.exe" else "bin/jpackage")
        .asFile.absolutePath

tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Creates a self-contained app image with its own Java runtime"
    dependsOn(tasks.named("installDist"))
    inputs.dir(layout.buildDirectory.dir("install/MCLauncher/lib"))
    inputs.property("appVersion", project.version)
    inputs.property("mainClass", application.mainClass)
    doFirst {
        commandLine(
            packageJpackage,
            "--type", "app-image",
            "--name", "MCLauncher",
            "--app-version", project.version.toString(),
            "--vendor", "Lorikowka",
            "--input", layout.buildDirectory.dir("install/MCLauncher/lib").get().asFile.absolutePath,
            "--main-jar", "MCLauncher-${project.version}.jar",
            "--main-class", application.mainClass.get(),
            "--dest", packageDestination.get().asFile.absolutePath,
            "--java-options", "-Dfile.encoding=UTF-8",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
        )
    }
    outputs.dir(packageDestination)
}

tasks.register<Zip>("packagePortableZip") {
    group = "distribution"
    description = "Packages the self-contained app image as a portable ZIP"
    dependsOn(tasks.named("packageApp"))
    from(packageDestination.map { it.dir("MCLauncher") }) {
        into("MCLauncher")
    }
    from("distribution/windows")
    archiveFileName.set("MCLauncher-${project.version}-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

val wixHome = layout.buildDirectory.dir("tools/wix314").get().asFile
val wixArchive = layout.buildDirectory.file("tools/wix314-binaries.zip").get().asFile
val wixUrl = "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip"
val wixSha256 = "6AC824E1642D6F7277D0ED7EA09411A508F6116BA6FAE0AA5F2C7DAA2FF43D31"
val msiOutput = layout.buildDirectory.file(
        "distributions/MCLauncher-${project.version}.msi")

tasks.register("prepareWix") {
    group = "distribution"
    description = "Downloads and verifies the official portable WiX 3.14.1 tools"
    outputs.dir(wixHome)
    doLast {
        if (File(wixHome, "candle.exe").isFile && File(wixHome, "light.exe").isFile) {
            return@doLast
        }

        Files.createDirectories(wixArchive.parentFile.toPath())
        val digest = MessageDigest.getInstance("SHA-256")
        if (wixArchive.isFile) {
            wixArchive.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        val existingHash = digest.digest().joinToString("") { "%02X".format(it) }
        if (existingHash != wixSha256) {
            val temporaryArchive = wixArchive.resolveSibling("wix314-binaries.zip.tmp")
            URI(wixUrl).toURL().openStream().use { input ->
                Files.copy(input, temporaryArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val downloadedHash = MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(temporaryArchive.toPath()))
                    .joinToString("") { "%02X".format(it) }
            check(downloadedHash == wixSha256) {
                "WiX archive SHA-256 mismatch: $downloadedHash"
            }
            Files.move(temporaryArchive.toPath(), wixArchive.toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
        }

        val temporaryDirectory = wixHome.resolveSibling("wix314-extract")
        delete(temporaryDirectory)
        Files.createDirectories(temporaryDirectory.toPath())
        val extractionRoot = temporaryDirectory.toPath().toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(wixArchive.toPath())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
            val target = extractionRoot.resolve(entry.name).normalize()
            require(target.startsWith(extractionRoot)) {
                    "Unsafe path in WiX archive: ${entry.name}"
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        check(File(temporaryDirectory, "candle.exe").isFile
                && File(temporaryDirectory, "light.exe").isFile) {
            "Portable WiX archive is missing candle.exe or light.exe"
        }
        delete(wixHome)
        Files.move(temporaryDirectory.toPath(), wixHome.toPath())
    }
}

tasks.register<Exec>("packageWindowsMsi") {
    group = "distribution"
    description = "Creates a per-user Windows MSI using the JDK jpackage tool"
    dependsOn(tasks.named("packageApp"))
    dependsOn(tasks.named("prepareWix"))
    inputs.dir(packageDestination.map { it.dir("MCLauncher") })
    outputs.file(msiOutput)
    environment("PATH", wixHome.absolutePath + ";" + System.getenv("PATH").orEmpty())
    doFirst {
        require(File(wixHome, "candle.exe").isFile && File(wixHome, "light.exe").isFile) {
            "WiX 3.14 binaries are required in ${wixHome.absolutePath}"
        }
        commandLine(
            packageJpackage,
            "--type", "msi",
            "--name", "MCLauncher",
            "--app-version", project.version.toString(),
            "--vendor", "Lorikowka",
            "--app-image", packageDestination.get().dir("MCLauncher").asFile.absolutePath,
            "--dest", layout.buildDirectory.dir("distributions").get().asFile.absolutePath,
            "--win-per-user-install",
            "--win-menu",
            "--win-menu-group", "MCLauncher",
            "--win-shortcut",
        )
    }
}

val exeOutput = layout.buildDirectory.file(
        "distributions/MCLauncher-${project.version}.exe")

tasks.register<Exec>("packageWindowsExe") {
    group = "distribution"
    description = "Creates a per-user Windows EXE installer using the JDK jpackage tool"
    dependsOn(tasks.named("packageApp"))
    dependsOn(tasks.named("prepareWix"))
    inputs.dir(packageDestination.map { it.dir("MCLauncher") })
    outputs.file(exeOutput)
    environment("PATH", wixHome.absolutePath + ";" + System.getenv("PATH").orEmpty())
    doFirst {
        require(File(wixHome, "candle.exe").isFile && File(wixHome, "light.exe").isFile) {
            "WiX 3.14 binaries are required in ${wixHome.absolutePath}"
        }
        commandLine(
            packageJpackage,
            "--type", "exe",
            "--name", "MCLauncher",
            "--app-version", project.version.toString(),
            "--vendor", "Lorikowka",
            "--app-image", packageDestination.get().dir("MCLauncher").asFile.absolutePath,
            "--dest", layout.buildDirectory.dir("distributions").get().asFile.absolutePath,
            "--win-per-user-install",
            "--win-menu",
            "--win-menu-group", "MCLauncher",
            "--win-shortcut",
        )
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Drives the real UI on the JavaFX thread and checks what the user would see:
//   gradlew uiCheck
tasks.register<JavaExec>("uiCheck") {
    group = "verification"
    description = "Builds the real window and asserts the login form and layout behave"
    mainClass.set("com.mymods.mclauncher.UiCheck")
    classpath = sourceSets["main"].runtimeClasspath
    // Software rendering keeps the check working over RDP and in VMs without a GPU.
    // JavaFX prints an "Unsupported JavaFX configuration" notice because it is loaded from the
    // classpath rather than the module path; that is expected here and harmless.
    jvmArgs = listOf(
        "-Dprism.order=sw",
        "-Dfile.encoding=UTF-8",
        // The check drives the window without a console stage of its own; LauncherView skips
        // opening one when this is set.
        "-Dmclauncher.uicheck=true",
    )
}

// Verifies Ely/authlib-injector/Adoptium endpoints still answer as expected:
//   gradlew probeServices
tasks.register<JavaExec>("probeServices") {
    group = "verification"
    description = "Checks the third-party service endpoints the launcher depends on"
    mainClass.set("com.mymods.mclauncher.ProbeServices")
    classpath = sourceSets["main"].runtimeClasspath
}

// Starts the real game and watches its log:
//   gradlew smokeLaunch "-PmcVersion=1.20.1" "-PtimeoutSeconds=180"
//   gradlew smokeLaunch "-PmcVersion=1.20.1" "-Ploader=fabric"
tasks.register<JavaExec>("smokeLaunch") {
    group = "verification"
    description = "Launches the game and checks that it reaches the loading stage"
    mainClass.set("com.mymods.mclauncher.SmokeLaunch")
    classpath = sourceSets["main"].runtimeClasspath
    args(providers.gradleProperty("mcVersion").getOrElse("1.20.1"))
    systemProperty("timeoutSeconds", providers.gradleProperty("timeoutSeconds").getOrElse("180"))
    // Empty by default, which means a plain vanilla launch. When set, the run asserts that this
    // loader specifically came up rather than accepting the vanilla markers.
    systemProperty("loader", providers.gradleProperty("loader").getOrElse(""))
    systemProperty("loaderVersion", providers.gradleProperty("loaderVersion").getOrElse(""))
    systemProperty("gameDir", providers.gradleProperty("gameDir").getOrElse(
        "${System.getProperty("user.home")}/MCLauncherHeadlessCheck"))
}

// Headless install + command-line check, for verifying the pipeline without the UI:
//   gradlew headlessCheck "-PmcVersion=1.20.1"
tasks.register<JavaExec>("headlessCheck") {
    group = "verification"
    description = "Downloads a version and prints the launch command"
    mainClass.set("com.mymods.mclauncher.HeadlessCheck")
    classpath = sourceSets["main"].runtimeClasspath
    args(providers.gradleProperty("mcVersion").getOrElse("1.20.1"))
}