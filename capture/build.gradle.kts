plugins {
    `java-library`
}

dependencies {
    implementation(project(":logging"))
}

tasks.register<Exec>("buildHotkeyHelper") {
    group = "build"
    description = "Builds native/evdev-hotkey (needs gcc and libevdev)"
    workingDir = rootProject.file("native/evdev-hotkey")
    commandLine("make")
}
