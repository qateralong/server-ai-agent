val jarName: String by project.extra
val appName: String by project.extra
val mainClassName: String by project.extra
val javaOptions: List<String> by project.extra
val buildDeb: Boolean by project.extra
val buildWinExe: Boolean by project.extra
val appDescription: String by project.extra

val appVersion: String = Regex("^(\\d+\\.\\d+\\.\\d+)").find(rootProject.version.toString())?.groupValues?.get(1) ?: "0.1.0"

val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

fun onPath(vararg names: String): Boolean =
    (System.getenv("PATH") ?: "").split(java.io.File.pathSeparator)
        .any { dir -> names.any { java.io.File(dir, it).canExecute() } }

val fatJar = tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Fat JAR with all dependencies: java -jar $jarName"
    archiveFileName.set(jarName)
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("release/jars"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to mainClassName,
            "Implementation-Title" to appName,
            "Implementation-Version" to rootProject.version.toString(),

            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
    val runtime = project.configurations.getByName("runtimeClasspath")
    dependsOn(runtime)
    from(project.extensions.getByType<SourceSetContainer>().getByName("main").output)
    from({ runtime.filter { it.name.endsWith(".jar") }.map { zipTree(it) } })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "module-info.class", "META-INF/versions/*/module-info.class")
}

fun jdkHome(): java.io.File {
    val launcher = project.extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    return launcher.get().metadata.installationPath.asFile
}

val jpackageInput = rootProject.layout.buildDirectory.dir("release/jpackage-input/$appName")

val stageJpackageInput = tasks.register<Sync>("stageJpackageInput") {
    dependsOn(fatJar)
    from(fatJar.map { it.archiveFile })
    into(jpackageInput)
}

fun jpackageArgs(type: String, dest: java.io.File): List<String> {
    val args = mutableListOf(
        jdkHome().resolve(if (isWindows) "bin/jpackage.exe" else "bin/jpackage").absolutePath,
        "--type", type,
        "--name", appName,
        "--app-version", appVersion,
        "--description", appDescription,
        "--vendor", "Server AI Agent",
        "--input", jpackageInput.get().asFile.absolutePath,
        "--main-jar", jarName,
        "--main-class", mainClassName,
        "--dest", dest.absolutePath,
    )
    javaOptions.forEach { args += listOf("--java-options", it) }
    if (type == "deb") {
        args += listOf("--linux-package-name", appName, "--linux-shortcut")
    }
    if (type == "exe") {

        args += listOf("--win-per-user-install", "--win-dir-chooser", "--win-menu", "--win-shortcut")
    }
    return args
}

val jpackageAppImage = tasks.register<Exec>("jpackageAppImage") {
    group = "distribution"
    description = "Native build (app-image) with a bundled JVM"
    dependsOn(stageJpackageInput)
    val dest = rootProject.layout.buildDirectory.dir("release/app-image").get().asFile
    doFirst {
        dest.resolve(appName).deleteRecursively()
        dest.mkdirs()
    }
    commandLine(jpackageArgs("app-image", dest))
}

val appImageTar = tasks.register<Tar>("appImageTar") {
    group = "distribution"
    description = "app-image packaged as tar.gz for the release (Linux)"
    dependsOn(jpackageAppImage)
    onlyIf { !isWindows }
    compression = Compression.GZIP
    archiveFileName.set("$appName-$appVersion-linux-x64.tar.gz")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("release/native"))
    from(rootProject.layout.buildDirectory.dir("release/app-image/$appName")) {
        into(appName)

        filesMatching(listOf("bin/**", "lib/runtime/bin/**", "lib/runtime/lib/jspawnhelper")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
}

val appImageZip = tasks.register<Zip>("appImageZip") {
    group = "distribution"
    description = "app-image packaged as zip for the release (Windows)"
    dependsOn(jpackageAppImage)
    onlyIf { isWindows }
    archiveFileName.set("$appName-$appVersion-windows-x64.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("release/native"))
    from(rootProject.layout.buildDirectory.dir("release/app-image/$appName")) {
        into(appName)
    }
}

val jpackageDeb = tasks.register<Exec>("jpackageDeb") {
    group = "distribution"
    description = "A .deb package for Ubuntu (needs dpkg-deb and fakeroot)"
    dependsOn(stageJpackageInput)
    onlyIf { buildDeb && !isWindows && onPath("dpkg-deb") }
    val dest = rootProject.layout.buildDirectory.dir("release/native").get().asFile
    doFirst { dest.mkdirs() }
    commandLine(jpackageArgs("deb", dest))
}

val jpackageWinExe = tasks.register<Exec>("jpackageWinExe") {
    group = "distribution"
    description = "A Windows .exe installer (needs WiX Toolset)"
    dependsOn(stageJpackageInput)
    onlyIf { buildWinExe && isWindows && onPath("candle.exe", "wix.exe") }
    val dest = rootProject.layout.buildDirectory.dir("release/native").get().asFile
    doFirst { dest.mkdirs() }
    commandLine(jpackageArgs("exe", dest))
    doLast {
        val produced = dest.resolve("$appName-$appVersion.exe")
        val target = dest.resolve("$appName-$appVersion-windows-x64.exe")
        if (produced.exists()) {
            target.delete()
            produced.renameTo(target)
        }
    }
}

tasks.register("packageNative") {
    group = "distribution"
    description = "Everything for the release on this OS: fat JAR; Linux -- app-image tar.gz and .deb; Windows -- zip and .exe"
    dependsOn(fatJar, appImageTar, jpackageDeb, appImageZip, jpackageWinExe)
}
