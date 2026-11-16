plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ubers.observador"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ubers.observador"
        minSdk = 30
        targetSdk = 36
        // Bump on every release and log it in CHANGELOG.md:
        // versionCode +1 always; versionName MAJOR.MINOR.PATCH (each plan phase = MINOR, fixes = PATCH)
        versionCode = 66
        versionName = "1.55.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // No key or password is stored in the project. A partial setup fails explicitly.
    val signingNames = listOf("OBSERVADOR_KEYSTORE", "OBSERVADOR_STORE_PASSWORD", "OBSERVADOR_KEY_ALIAS", "OBSERVADOR_KEY_PASSWORD")
    val signingValues = signingNames.map { providers.environmentVariable(it).orNull }
    val hasReleaseSigning = signingValues.any { !it.isNullOrBlank() }
    if (hasReleaseSigning && signingValues.any { it.isNullOrBlank() }) {
        throw GradleException("Firma incompleta: configura las cuatro variables OBSERVADOR de firma.")
    }
    if (hasReleaseSigning) {
        val externalStore = file(signingValues[0]!!).canonicalFile
        if (externalStore.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
            throw GradleException("El almacén de firma debe estar fuera del repositorio.")
        }
        signingConfigs.create("externalRelease") {
            storeFile = externalStore
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("externalRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Experimental only: never substituted for the normal release or debug APK.
        create("optimized") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Room schema history (app/schemas), packaged for MigrationTestHelper in instrumented tests
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * "Novedades de esta versión" in Ajustes. Reads the "## Novedades en la app" section of
 * docs/releases/changelogs/<versionName>.md and packages its bullets as assets/novedades.txt.
 * The build fails if the release card or that section is missing, so every version ships its notes.
 */
abstract class ReleaseNotesTask : DefaultTask() {
    @get:Input abstract val versionName: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val changelog: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun generate() {
        val version = versionName.get()
        val card = changelog.singleFile
        if (!card.exists()) throw GradleException(
            "Falta docs/releases/changelogs/$version.md con la sección «## Novedades en la app».")
        val lines = card.readLines(Charsets.UTF_8)
        val start = lines.indexOfFirst { it.trim().equals("## Novedades en la app", ignoreCase = true) }
        if (start < 0) throw GradleException("docs/releases/changelogs/$version.md no tiene «## Novedades en la app».")
        val items = lines.drop(start + 1)
            .takeWhile { !it.startsWith("## ") }
            .filter { it.trimStart().startsWith("- ") }
            .map { line ->
                line.trimStart().removePrefix("- ").trim()
                    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                    .replace("`", "").replace("**", "")
            }
        if (items.isEmpty()) throw GradleException("«## Novedades en la app» de $version.md está vacía.")
        val out = outputDir.get().file("novedades.txt").asFile
        out.parentFile.mkdirs()
        out.writeText(items.joinToString("\n"), Charsets.UTF_8)
    }
}

val releaseNotes = tasks.register<ReleaseNotesTask>("generateReleaseNotes") {
    val version = android.defaultConfig.versionName ?: error("versionName sin definir")
    versionName.set(version)
    changelog.from(rootProject.layout.projectDirectory.file("docs/releases/changelogs/$version.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(releaseNotes, ReleaseNotesTask::outputDir)
    }
}

dependencies {
    constraints {
        implementation("androidx.fragment:fragment:1.7.1") {
            because("The legacy Fragment pulled by Play Services is incompatible with ActivityResult APIs")
        }
    }
    // Compose BOM managed
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Location (Fase 8E): fused provider, only while measuring kilometres
    implementation(libs.play.services.location)

    // 1.28.0: charts of the Violeta theme (Apache 2.0)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    // 1.54.0: camera reading of the TPMS kit card
    implementation(libs.zxing.android.embedded)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
