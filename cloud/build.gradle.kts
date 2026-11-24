import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Rosenweg Cloud: Anmeldung (Nextcloud Login Flow v2) und WebDAV fuer die
// Rosenweg-Apps. Eigenes Modul, damit Commons ohne Netz bleibt — nur Apps,
// die selbst hochladen, binden es ein und bekommen damit INTERNET.
plugins {
    alias(libs.plugins.library)
    `maven-publish`
}

group = "ch.rosenweg4303"
version = findProperty("CLOUD_VERSION")?.toString() ?: "0.6.0"

android {
    namespace = "ch.rosenweg4303.cloud"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()
    defaultConfig {
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    publishing {
        singleVariant("release") {}
    }
    compileOptions {
        val v = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = v
        targetCompatibility = v
    }
    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get()))
}

publishing.publications {
    create<MavenPublication>("release") {
        artifactId = "cloud"
        afterEvaluate { from(components["release"]) }
    }
}

dependencies {
    api(libs.okhttp)
    implementation(libs.androidx.work)
}
