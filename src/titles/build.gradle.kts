import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
}

layout.buildDirectory = layout.settingsDirectory.dir("build/work/titles")

room3 {
  schemaDirectory("$projectDir/schema")
}

android {
  namespace = "app.hakusan.titles"
  buildToolsVersion = "36.0.0"
  compileSdk = 37

  defaultConfig {
    minSdk = 33
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  testOptions {
    targetSdk = 37
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
  }
}

kotlin {
  jvmToolchain(25)

  compilerOptions {
    apiVersion = KotlinVersion.KOTLIN_2_4
    jvmTarget = JvmTarget.JVM_25
    languageVersion = KotlinVersion.KOTLIN_2_4
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  api(libs.kotlinx.coroutines.core)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)

  androidTestImplementation(libs.androidx.test.junit)
  androidTestRuntimeOnly(libs.androidx.test.runner)
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
