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
version = findProperty("UPDATER_VERSION")?.toString() ?: "0.1.8"

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
    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    testImplementation("junit:junit:4.13.2")
    // Vorschaubild der Update-Seite (UpdateSeiteBildTest, nur mit RW_BILDER=<ordner>)
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
}
