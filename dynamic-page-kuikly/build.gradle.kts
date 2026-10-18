plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    `maven-publish`
}
kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    iosArm64(); iosSimulatorArm64(); iosX64(); ohosArm64()
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2-1.0.0")
            api(project(":dynamic-page-core"))
            api("com.tencent.kuikly-open:core:2.29.1-2.0.21-ohos")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2-1.0.0")
        }
        androidMain.dependencies {
            api("com.tencent.kuikly-open:core-render-android:2.29.1-2.0.21-ohos")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2-1.0.0")
        }
    }
    targets.getByName<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>("ohosArm64") {
        compilations.getByName("main").cinterops.create("kuiklyModule") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/kuiklyModule.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
            // 仅绑定纯 C ABI，不需要把 Kuikly 的旧平台 klib 传给 cinterop 解析器。
            dependencyFiles = files()
        }
    }
}
android {
    namespace = "io.github.gycrosskit.dynamicpage.kuikly"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
