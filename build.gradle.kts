import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    java
}

allprojects {
    group = "com.bebebe"

    version = (findProperty("releaseVersion") as String?) ?: "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    val catalog = rootProject.extensions
        .getByType(VersionCatalogsExtension::class.java)
        .named("libs")

    fun lib(alias: String) = catalog.findLibrary(alias).orElseThrow {
        IllegalStateException("No library '$alias' in the version catalog")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(catalog.findVersion("java").orElseThrow().requiredVersion))
        }
    }

    dependencies {
        "implementation"(lib("slf4j-api"))

        "testImplementation"(lib("junit-jupiter"))
        "testRuntimeOnly"(lib("junit-platform-launcher"))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        // The interface language follows the system locale by default, and the tests assert
        // English wording -- so they must not depend on the locale of the machine they run on.
        systemProperty("user.language", "en")
        systemProperty("user.country", "US")
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
}

tasks.register("releaseArtifacts") {
    group = "distribution"
    description = "Linux part of the release: three JARs, app-image tar.gz x3, .deb x3, " +
        ".pkg.tar.zst x2 (only where makepkg is available) -> build/release/{jars,native}"
    dependsOn(":server-app:packageNative", ":client-app:packageNative", ":supervisor-app:packageNative")
}

//Bebebe

tasks.register("releaseArtifactsWindows") {
    group = "distribution"
    description = "Windows part of the release: thin client and the GUI build -- app-image zip and .exe installer"
    dependsOn(":client-app:packageNative", ":supervisor-app:packageNative")
}
