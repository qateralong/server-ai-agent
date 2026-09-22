plugins {
    `java-library`
}

dependencies {
    api(libs.nightconfig.core)

    implementation(project(":logging"))
    implementation(libs.nightconfig.toml)
}
