// The Gradle plugin is its own Gradle build (Gradle plugins are built with Gradle); it uses the
// engine the Maven build installs, so run `mvn install` at the repository root first.
plugins {
    `java-gradle-plugin`
}

group = "org.arodnap"
version = "2.0.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("org.arodnap:arodnap-engine:$version")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("arodnap") {
            id = "org.arodnap"
            implementationClass = "org.arodnap.gradle.ArodnapPlugin"
            displayName = "Arodnap"
            description = "Repairs resource leaks in a Gradle build: ./gradlew arodnapRepair."
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // The TestKit tests run real repairs; they are opt-in like the other end-to-end tests.
    environment("ARODNAP_E2E", System.getenv("ARODNAP_E2E") ?: "")
    systemProperty("arodnap.testProjects", layout.projectDirectory.dir("../test-projects").asFile.absolutePath)
}
