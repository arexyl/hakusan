import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

buildscript {
  configurations.classpath {
    resolutionStrategy.activateDependencyLocking()
  }
}

plugins {
  `java-gradle-plugin`
  alias(libs.plugins.kotlin.jvm)
}

// Root :clean preserves build/cache, so cleaning the main build keeps the
// compiled plugins and the Configuration Cache entries that depend on them.
layout.buildDirectory =
  layout.settingsDirectory.dir("../../build/cache/build-logic")

// Gradle runs the plugins with its embedded Kotlin, so they compile against
// that Kotlin's language, API, and standard library.
val gradleKotlin =
  KotlinVersion.fromVersion(embeddedKotlinVersion.substringBeforeLast('.'))

kotlin {
  explicitApi()
  jvmToolchain(libs.versions.jdk.get().toInt())
  coreLibrariesVersion = embeddedKotlinVersion

  compilerOptions {
    apiVersion = gradleKotlin
    languageVersion = gradleKotlin
    jvmTarget = JvmTarget.fromTarget(libs.versions.jdk.get())
  }
}

gradlePlugin {
  plugins {
    register("androidApplication") {
      id = "hakusan.android.application"
      implementationClass = "app.hakusan.build.AndroidApplicationPlugin"
    }

    register("androidLibrary") {
      id = "hakusan.android.library"
      implementationClass = "app.hakusan.build.AndroidLibraryPlugin"
    }
  }
}

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
  compileOnly(kotlin("gradle-plugin-api", libs.versions.kotlin.get()))
}
