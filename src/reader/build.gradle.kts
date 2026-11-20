import app.hakusan.build.CheckBindings
import app.hakusan.build.FilePropertyArgument
import app.hakusan.build.PromoteFixtures
import app.hakusan.build.VerifyFixtures

plugins {
  id("hakusan.android.library")
}

android {
  namespace = "app.hakusan.reader"

  sourceSets {
    named("main") {
      kotlin.directories +=
        layout.settingsDirectory.dir("share/bindings/kotlin").asFile.path
    }
  }
}

tasks.withType<Test>().configureEach {
  jvmArgumentProviders.add(
    objects.newInstance<FilePropertyArgument>().apply {
      property = "app.hakusan.reader.fixtureManifest"
      file = layout.settingsDirectory.file("share/reader/manifest.json")
    }
  )

  jvmArgumentProviders.add(
    objects.newInstance<FilePropertyArgument>().apply {
      property = "app.hakusan.reader.bindingsTable"
      file = layout.settingsDirectory.file("share/bindings/abi.tsv")
    }
  )
}

dependencies {
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.kotlinx.serialization.json)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

val checkBindings =
  tasks.register<CheckBindings>("checkBindings") {
    group = "verification"
    description = "Fails when share/bindings lacks the native ABI files."
    checkout = layout.settingsDirectory.dir("share/bindings")

    required.from(
      listOf(
          ".editorconfig",
          "abi.tsv",
          "include/san.h",
          "kotlin/app/hakusan/reader/san/SanAbi.kt",
        )
        .map { checkout.file(it) }
    )

    report = layout.buildDirectory.file("checkBindings/report.txt")
  }

tasks.named("preBuild") { dependsOn(checkBindings) }

tasks.register<VerifyFixtures>("verifyFixtures") {
  group = "verification"
  description = "Checks the fixture corpus and its tools against the manifest."
  corpus = layout.settingsDirectory.dir("share/reader")
  tools.from(fileTree("fixtures") { include("*.py") })
  report = layout.buildDirectory.file("verifyFixtures/report.txt")
}

tasks.register<PromoteFixtures>("promoteFixtures") {
  group = "fixtures"
  description = "Replaces the reviewed corpus with the generated corpus."
  generated = layout.buildDirectory.dir("fixtures/corpus")
  manifest = generated.file("manifest.json")
  corpus = layout.settingsDirectory.dir("share/reader")
  finalizedBy("verifyFixtures")
}
