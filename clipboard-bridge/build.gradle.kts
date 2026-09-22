plugins {
    `java-library`
}

dependencies {
    api(project(":transport"))

    implementation(project(":logging"))
}
