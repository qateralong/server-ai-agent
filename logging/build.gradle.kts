plugins {
    `java-library`
}

dependencies {
    api(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.jackson.databind)

    testImplementation(libs.jackson.databind)
    testImplementation(libs.logback.classic)
}
