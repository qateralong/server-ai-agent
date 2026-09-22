plugins {
    application
    alias(libs.plugins.javafx)
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.base")
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":assembly"))
    implementation(project(":config-store"))
    implementation(project(":logging"))
    implementation(libs.sqlite.jdbc)
}

application {
    mainClass.set("com.bebebe.agent.supervisor.SupervisorMain")

    applicationDefaultJvmArgs = listOf("--enable-native-access=javafx.graphics", "--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {

    workingDir = rootProject.projectDir

    System.getProperty("bebebe.config")?.let { systemProperty("bebebe.config", it) }

    jvmArgs("--enable-native-access=javafx.graphics", "--enable-native-access=ALL-UNNAMED")
}

extra["jarName"] = "standalone-agent.jar"
extra["appName"] = "server-ai-agent"
extra["mainClassName"] = "com.bebebe.agent.supervisor.SupervisorMain"
extra["javaOptions"] = listOf("--enable-native-access=javafx.graphics", "--enable-native-access=ALL-UNNAMED")
extra["buildDeb"] = false
extra["buildWinExe"] = false
extra["appDescription"] = "Server AI Agent: standalone AI agent with a window (Stage 1)"
apply(from = rootProject.file("gradle/packaging.gradle.kts"))
