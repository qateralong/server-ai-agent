plugins {
    `java-library`
}

dependencies {
    api(project(":config-store"))
    api(project(":transport"))

    implementation(project(":logging"))
}
