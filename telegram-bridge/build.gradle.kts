plugins {
    `java-library`
}

dependencies {
    api(project(":agent-core"))
    api(project(":script-library"))
    api(project(":memory-store"))
    api(project(":scheduler"))
    api(project(":notes-store"))

    api(project(":config-store"))
    api(project(":logging"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.annotations)
}
