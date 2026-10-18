import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
            content { includeVersionByRegex(".*", ".*", ".*(-KBA-011|-1\\.0\\.0|-1\\.1\\.0-04|-0\\.3\\.0)") }
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            // 镜像的腾讯 KMP 元数据不完整；避免命中 POM 后卡住同版 artifact 回退。
            content { excludeGroupByRegex("com\\.tencent.*") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
            content { includeVersionByRegex(".*", ".*", ".*(-KBA-011|-1\\.0\\.0|-1\\.1\\.0-04|-0\\.3\\.0)") }
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            // 镜像的腾讯 KMP 元数据不完整；避免命中 POM 后卡住同版 artifact 回退。
            content { excludeGroupByRegex("com\\.tencent.*") }
        }
        google()
        mavenCentral()
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
    }
}

rootProject.name = "dynamic-page"
include(":dynamic-page-core", ":dynamic-page-kuikly")
