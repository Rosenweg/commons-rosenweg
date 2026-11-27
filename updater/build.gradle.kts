import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Rosenweg Updater (Stefan 25.11.2026: "alle Apps brauchen einen Updater, wenn Geraet nicht da
// ist"). Eigenes Modul: Telefon und Messenger binden es direkt ein, die Nextcloud-Apps ueber
// das Cloud-Modul. Bringt INTERNET und REQUEST_INSTALL_PACKAGES mit.
plugins {
    alias(libs.plugins.library)
    `maven-publish`
}

group = "ch.rosenweg4303"
version = findProperty("UPDATER_VERSION")?.toString() ?: "0.1.5"

android {
    namespace = "ch.rosenweg4303.updater"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()
    defaultConfig {
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
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
        artifactId = "updater"
        afterEvaluate { from(components["release"]) }
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.androidx.work)
}
