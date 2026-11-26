plugins {
    alias(libs.plugins.android).apply(false)
    alias(libs.plugins.ksp).apply(false)
    alias(libs.plugins.parcelize).apply(false)
    alias(libs.plugins.library).apply(false)
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.detekt).apply(false)
}

tasks.register<Delete>("clean") {
    delete {
        layout.buildDirectory.asFile
    }
}

// Rosenweg: alle Module mit maven-publish auch nach GitHub Packages (Maven, Org Rosenweg).
// Workflow .github/workflows/pakete.yml bei Tag v*; lokal mit gpr.user/gpr.key aus
// ~/.gradle/gradle.properties. publishToMavenLocal geht wie bisher.
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Rosenweg/commons-rosenweg")
                    credentials {
                        username = findProperty("gpr.user")?.toString() ?: System.getenv("GITHUB_ACTOR")
                        password = findProperty("gpr.key")?.toString() ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}
