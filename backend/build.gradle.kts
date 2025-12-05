plugins {
    `maven-publish`
    kotlin("jvm") version "1.9.24"
}

dependencies {
    implementation("com.displee:disio:2.2")
    implementation("com.displee:rs-cache-library:8.0.1")
    implementation("org.apache.commons:commons-lang3:3.18.0")
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/blurite/cs2-editor")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
    publications {
        register<MavenPublication>("gpr") {
            from(components["java"])
        }
    }
}
