import java.time.OffsetDateTime

plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":agent-core"))
    api(project(":config-store"))
    api(project(":telegram-bridge"))
    api(project(":stt-bridge"))
    api(project(":tts-bridge"))
    api(project(":clipboard-bridge"))
    api(project(":script-library"))
    api(project(":notes-store"))
    api(project(":watchdog"))
    api(project(":transport"))
    implementation(project(":logging"))

    implementation(libs.sqlite.jdbc)

    testImplementation(libs.logback.classic)

    testFixturesImplementation(libs.jackson.databind)
}

fun git(vararg args: String): String = try {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootProject.projectDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    ""
}

val buildInfoDir = layout.buildDirectory.dir("generated/build-info")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val commit = git("rev-parse", "HEAD")
    val shortCommit = git("rev-parse", "--short", "HEAD")
    val branch = git("rev-parse", "--abbrev-ref", "HEAD")
    val dirty = git("status", "--porcelain").isNotEmpty()
    val remote = git("remote", "get-url", "origin")
    val commitTime = git("log", "-1", "--format=%cI")
    val now = OffsetDateTime.now().toString()
    inputs.property("commit", commit)
    inputs.property("dirty", dirty)
    outputs.dir(buildInfoDir)
    doLast {
        val file = buildInfoDir.get().file("build-info.properties").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            commit=$commit
            commit.short=$shortCommit
            commit.time=$commitTime
            branch=$branch
            dirty=$dirty
            remote=$remote
            build.time=$now
            """.trimIndent() + "\n"
        )
    }
}

sourceSets.main {
    resources.srcDir(generateBuildInfo)
}
