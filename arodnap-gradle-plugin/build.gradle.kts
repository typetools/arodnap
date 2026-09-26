// The Gradle plugin is its own Gradle build (Gradle plugins are built with Gradle); it uses the
// engine the Maven build installs, so run `mvn install` at the repository root first.
plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "org.arodnap"
// One version for the whole repository: the Maven build's.
version = Regex("<artifactId>arodnap-parent</artifactId>\\s*<version>([^<]+)</version>")
    .find(file("../pom.xml").readText())!!.groupValues[1]

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
    website = "https://github.com/typetools/arodnap"
    vcsUrl = "https://github.com/typetools/arodnap"
    plugins {
        create("arodnap") {
            id = "org.arodnap"
            implementationClass = "org.arodnap.gradle.ArodnapPlugin"
            displayName = "Arodnap"
            description = "Finds and repairs resource leaks in a Gradle build: ./gradlew arodnapRepair."
            tags = listOf("resource-leaks", "static-analysis", "checker-framework", "repair")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // The TestKit tests run real repairs; they are opt-in like the other end-to-end tests.
    environment("ARODNAP_E2E", System.getenv("ARODNAP_E2E") ?: "")
    systemProperty("arodnap.testProjects", layout.projectDirectory.dir("../test-projects").asFile.absolutePath)
}

tasks.javadoc {
    // Task properties are documented on the extension, not on each getter.
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}
