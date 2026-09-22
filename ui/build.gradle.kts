plugins {
    `java-library`
    alias(libs.plugins.javafx)
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.base")
}

dependencies {
    api(libs.atlantafx.base)
    api(project(":agent-core"))
    api(project(":config-store"))
    api(project(":memory-store"))
    api(project(":notes-store"))
    api(project(":ollama-client"))

    implementation(project(":logging"))
}
