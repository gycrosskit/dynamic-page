plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0" apply false
    kotlin("plugin.serialization") version "2.2.21-1.0.0" apply false
    id("com.android.library") version "8.10.1" apply false
}
allprojects {
    group = providers.environmentVariable("GROUP").orElse("com.github.gycrosskit.dynamic-page").get()
    version = providers.environmentVariable("VERSION").orElse("0.1.0-rc.1").get()

    plugins.withId("maven-publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            repositories {
                maven {
                    name = "Staging"
                    url = uri(providers.environmentVariable("DYNAMIC_PAGE_STAGING_DIR")
                        .orElse(rootProject.layout.buildDirectory.dir("maven").get().asFile.absolutePath).get())
                }
            }
            publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
                pom {
                    url.set("https://github.com/gycrosskit/dynamic-page")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                }
            }
        }
    }
}
