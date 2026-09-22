plugins {
    application
}

dependencies {
    implementation(project(":assembly"))
    implementation(project(":transport"))
    implementation(project(":config-store"))
    implementation(project(":logging"))
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.logback.classic)
    testImplementation(testFixtures(project(":assembly")))
    testImplementation(project(":client-app"))
    testImplementation(libs.jackson.databind)
}

application {
    mainClass.set("com.bebebe.agent.server.ServerMain")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    System.getProperty("bebebe.config")?.let { systemProperty("bebebe.config", it) }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

extra["jarName"] = "server.jar"
extra["appName"] = "server-ai-agent-server"
extra["mainClassName"] = "com.bebebe.agent.server.ServerMain"
extra["javaOptions"] = listOf("--enable-native-access=ALL-UNNAMED")
extra["buildDeb"] = true
extra["buildWinExe"] = false
// No Arch package: the server is meant to live on a headless Ubuntu box, and its
// systemd unit is the Debian/Ubuntu path. Arch gets the client and the GUI build.
extra["buildArchPkg"] = false
extra["desktopEntry"] = false
extra["displayName"] = "Server AI Agent Server"
extra["appDescription"] = "Server AI Agent: headless AI agent server (Telegram, transport for thin clients)"
apply(from = rootProject.file("gradle/packaging.gradle.kts"))
