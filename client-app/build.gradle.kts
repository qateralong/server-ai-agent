plugins {
    application
}

dependencies {
    implementation(project(":transport"))
    implementation(project(":script-runtime"))
    implementation(project(":clipboard-bridge"))
    implementation(project(":capture"))
    implementation(project(":config-store"))
    implementation(project(":logging"))

    testImplementation(libs.logback.classic)
}

application {
    mainClass.set("com.bebebe.agent.client.ClientMain")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    System.getProperty("bebebe.client.config")?.let { systemProperty("bebebe.client.config", it) }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

extra["jarName"] = "client.jar"
extra["appName"] = "server-ai-agent-client"
extra["mainClassName"] = "com.bebebe.agent.client.ClientMain"
extra["javaOptions"] = listOf("--enable-native-access=ALL-UNNAMED")
extra["buildDeb"] = true
extra["buildWinExe"] = true
extra["appDescription"] = "Server AI Agent: thin client (scripts, clipboard, push-to-talk)"
apply(from = rootProject.file("gradle/packaging.gradle.kts"))
