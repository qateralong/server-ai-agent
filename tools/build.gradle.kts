plugins {
    `java-library`
}

dependencies {
    api(project(":config-store"))
    api(project(":notes-store"))
    api(project(":transport"))

    implementation(project(":logging"))
    implementation(libs.jackson.databind)

    testImplementation(libs.jackson.databind)
}
