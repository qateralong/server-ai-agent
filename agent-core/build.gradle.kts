plugins {
    `java-library`
}

dependencies {
    api(project(":ollama-client"))

    implementation(project(":logging"))
    api(project(":tools"))
    api(project(":memory-store"))
    implementation(project(":notes-store"))
    api(project(":script-runtime"))
    api(project(":script-library"))
    api(project(":scheduler"))
    api(project(":watchdog"))

    testImplementation(project(":config-store"))
    testImplementation(libs.logback.classic)
}
