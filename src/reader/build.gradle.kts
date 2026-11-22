import app.hakusan.build.CheckBindings
import app.hakusan.build.FilePropertyArgument
import app.hakusan.build.PromoteFixtures
import app.hakusan.build.StageFixtures
import app.hakusan.build.StageLocalSan
import app.hakusan.build.VerifyFixtures
import app.hakusan.build.localSan

plugins {
  id("hakusan.android.library")
}

// The staged set of a local San build, which this project then packages.
val localSan = localSan()

android {
  namespace = "app.hakusan.reader"

  defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")

    // The tests of the San library run only in a build that packages it.
    if (localSan.isPresent) {
      testInstrumentationRunnerArguments["app.hakusan.reader.san"] = "local"
    }
  }

  testOptions {
    targetSdk = libs.versions.sdk.target.get().toInt()
  }

  sourceSets {
    named("main") {
      kotlin.directories +=
        layout.settingsDirectory.dir("share/bindings/kotlin").asFile.path
    }
  }
}

// Each variant has tasks of its own, because the plugin gives the staged
// directory of a task to one variant.
androidComponents {
  onVariants { variant ->
    val name = variant.name.replaceFirstChar { it.uppercase() }

    if (localSan.isPresent) {
      val stageLocalSan =
        tasks.register<StageLocalSan>("stage${name}LocalSan") {
          group = "build"
          description = "Stages the San library of a checked local San build."
          localSet = localSan.map { layout.settingsDirectory.dir(it) }
          repository = layout.settingsDirectory
        }

      variant.sources.jniLibs?.addGeneratedSourceDirectory(
        stageLocalSan,
        StageLocalSan::jniLibs,
      )

      variant.sources.assets?.addGeneratedSourceDirectory(
        stageLocalSan,
        StageLocalSan::assets,
      )
    }

    // The pages that the device tests decode, with the manifest that states
    // their hashes. The tests check each file against it.
    val stageFixtures =
      tasks.register<StageFixtures>("stage${name}AndroidTestFixtures") {
        group = "build"
        description = "Stages the fixtures of the device tests as assets."
        corpus = layout.settingsDirectory.dir("share/reader")

        paths =
          listOf(
            "manifest.json",
            "journey/ordinary.png",
            "journey/long.png",
            "qualification/fault/png-truncated-prefix.png",
          )
      }

    variant.androidTest
      ?.sources
      ?.assets
      ?.addGeneratedSourceDirectory(stageFixtures, StageFixtures::staged)
  }
}

tasks.withType<Test>().configureEach {
  // The loader's test calls System.loadLibrary, which the JDK restricts to
  // code with native access.
  jvmArgs("--enable-native-access=ALL-UNNAMED")

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

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.junit)
  androidTestRuntimeOnly(libs.androidx.test.runner)
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
