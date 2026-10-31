buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
    dependencies {
        val kotlinVersion = "2.0.21"
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        // Android Gradle Plugin は Android SDK がある環境でだけ読み込む（settings.gradle.kts と同じ条件）
        val androidSdkAvailable = System.getenv("ANDROID_HOME") != null ||
            System.getenv("ANDROID_SDK_ROOT") != null ||
            rootProject.file("local.properties").exists()
        if (androidSdkAvailable) {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
        }
    }
}
