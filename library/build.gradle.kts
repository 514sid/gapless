plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("maven-publish")
    id("signing")
}

android {
    namespace = "io.github._514sid.gapless"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId    = project.properties["GROUP"].toString()
                artifactId = project.properties["ARTIFACT"].toString()
                version    = project.properties["VERSION"].toString()

                pom {
                    name        = "Gapless"
                    description = "Gapless media player composable for Android — video, image, and web assets with optional scheduling."
                    url         = "https://github.com/514sid/gapless"

                    licenses {
                        license {
                            name = "MIT License"
                            url  = "https://opensource.org/licenses/MIT"
                        }
                    }

                    developers {
                        developer {
                            id   = "514sid"
                            name = "514sid"
                        }
                    }

                    scm {
                        connection          = "scm:git:git://github.com/514sid/gapless.git"
                        developerConnection = "scm:git:ssh://github.com/514sid/gapless.git"
                        url                 = "https://github.com/514sid/gapless"
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url  = uri("https://maven.pkg.github.com/514sid/gapless")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
            maven {
                name = "OSSRH"
                url  = if (version.toString().endsWith("SNAPSHOT")) {
                    uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                } else {
                    uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                }
                credentials {
                    username = System.getenv("OSSRH_USERNAME")
                    password = System.getenv("OSSRH_PASSWORD")
                }
            }
        }
    }

    signing {
        val key      = System.getenv("GPG_SIGNING_KEY")
        val password = System.getenv("GPG_SIGNING_PASSWORD")
        if (!key.isNullOrBlank() && !password.isNullOrBlank()) {
            useInMemoryPgpKeys(key, password)
            sign(publishing.publications["release"])
        }
    }
}
