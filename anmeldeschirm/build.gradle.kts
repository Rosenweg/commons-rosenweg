import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Rosenweg Anmeldeschirm: Aussehen, Texte und Kartenleser des Sperrbildschirms der geteilten
// Telefone (Rosenweg Geraet) - EINMAL hier, genutzt von Rosenweg Geraet (SperrActivity) und vom
// Anmeldeschirm der Apps auf privaten Handys (cloud: RosenwegAnmeldeSchirm, Flutter ueber
// rosenweg_basis). Ohne Netz und ohne weitere Abhaengigkeiten; minSdk 21 wie Rosenweg Geraet.
plugins {
    alias(libs.plugins.library)
    `maven-publish`
}

group = "ch.rosenweg4303"
version = findProperty("ANMELDESCHIRM_VERSION")?.toString() ?: "0.1.0"

android {
    namespace = "ch.rosenweg4303.anmeldeschirm"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()
    defaultConfig {
        minSdk = 21
        // Rosenweg Geraet baut noch mit compileSdk 35 (AGP 8.2): nichts Neueres verlangen
        aarMetadata { minCompileSdk = 21 }
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
        artifactId = "anmeldeschirm"
        afterEvaluate { from(components["release"]) }
    }
}
