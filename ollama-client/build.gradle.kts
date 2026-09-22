plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.annotations)

    implementation(libs.anthropic.java)

    implementation(project(":config-store"))
    implementation(project(":logging"))
}
