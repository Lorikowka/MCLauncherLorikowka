plugins {
    application
}

group = "com.mymods.mclauncher"
version = "0.1.0"

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

    listOf("base", "graphics", "controls", "fxml").forEach { module ->
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