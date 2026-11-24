plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("maven-publish")
    id("signing")
}

android {
    // Must be a valid Android package name (no segment may start with "_"), or AAPT rejects the
    // release manifest. It only names the generated manifest package: the library has no
    // resources, and the Kotlin sources keep the io.github._514sid.gapless package.
    namespace = "io.github.gapless514sid"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    // Public declarations must state their visibility, so internals can't leak into the API by accident.
    explicitApi()
    // The library itself uses its experimental API freely; only callers have to opt in.
    compilerOptions { optIn.add("io.github._514sid.gapless.ExperimentalGaplessApi") }
}

/** The Android runtime jar Robolectric runs on, resolved by Gradle. See the test task below. */
val robolectricRuntime: Configuration by configurations.creating

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.smoothstreaming)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric runs on JUnit 4; the vintage engine runs those tests alongside the JUnit 5 ones.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

    robolectricRuntime(libs.robolectric.android.all) { isTransitive = false }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Robolectric would otherwise download this jar itself while the tests run, and a failed
    // download fails the whole test class. Gradle fetches it with retries and caches it instead.
    val runtime = robolectricRuntime
    inputs.files(runtime)
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Drobolectric.offline=true",
            "-Drobolectric.dependency.dir=${runtime.singleFile.parentFile.absolutePath}",
        )
    }
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
                name = "CentralPortalBundle"
                url  = uri(layout.buildDirectory.dir("central-bundle").get().asFile)
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