plugins {
    `java-library`
}

dependencies {
    api(project(":config-store"))
    api(libs.jackson.databind)

    implementation(libs.java.websocket)

    implementation(project(":logging"))

    testImplementation(libs.java.websocket)
    testImplementation(libs.logback.classic)
}
