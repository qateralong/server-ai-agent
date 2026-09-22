plugins {
    `java-library`
}

dependencies {
    api(project(":agent-core"))
    api(project(":capture"))

    implementation(project(":config-store"))
    implementation(project(":logging"))
}
