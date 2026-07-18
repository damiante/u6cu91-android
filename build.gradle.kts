plugins {
    id("com.android.library") version "9.2.1"
    `maven-publish`
}

android {
    namespace = "com.damiantesta.u6cu91"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Flow/StateFlow appear in the public API, so consumers need coroutines on their compile
    // classpath too — hence api, not implementation.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

// Publication consumed by JitPack (or `publishToMavenLocal` for local testing). JitPack
// rewrites the coordinates to com.github.<user>:<repo>:<tag>, so these values only matter
// for mavenLocal use.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.damiantesta"
            artifactId = "u6cu91-android"
            version = "0.1.1"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
