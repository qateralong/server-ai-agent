plugins {
    `java-library`
}

dependencies {
    api(project(":config-store"))

    implementation(project(":logging"))
    implementation(libs.sqlite.jdbc)
}
