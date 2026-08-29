import java.util.Locale
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyThirdPartyNotices : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticesFile: RegularFileProperty

    @get:Input
    abstract val artifactCoordinates: ListProperty<String>

    @TaskAction
    fun verify() {
        val coordinatePattern = Regex("""`([^`:\s]+):([^`:\s]+):([^`:\s]+)`""")
        val declaredCoordinates = coordinatePattern
            .findAll(noticesFile.asFile.get().readText())
            .map { match -> match.groupValues.drop(1).joinToString(":") }
            .toSortedSet()
        val resolvedCoordinates = artifactCoordinates.get().toSortedSet()
        val missingCoordinates = resolvedCoordinates - declaredCoordinates
        val unexpectedCoordinates = declaredCoordinates - resolvedCoordinates

        check(missingCoordinates.isEmpty() && unexpectedCoordinates.isEmpty()) {
            buildString {
                appendLine("Third-party notices do not match releaseRuntimeClasspath artifacts.")
                if (missingCoordinates.isNotEmpty()) {
                    appendLine("Missing coordinates:")
                    missingCoordinates.forEach { appendLine("  $it") }
                }
                if (unexpectedCoordinates.isNotEmpty()) {
                    appendLine("Unexpected coordinates:")
                    unexpectedCoordinates.forEach { appendLine("  $it") }
                }
            }.trimEnd()
        }
    }
}

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun secret(name: String): String? = providers.gradleProperty(name).orNull
    ?: providers.environmentVariable(name).orNull

val fdroidBuild = providers.gradleProperty("fdroidBuild")
    .map { value ->
        require(value == "true" || value == "false") {
            "fdroidBuild must be either true or false"
        }
        value.toBooleanStrict()
    }
    .getOrElse(false)
val releaseRequested = gradle.startParameter.taskNames.any { requestedTask ->
    val taskName = requestedTask.substringAfterLast(':').lowercase(Locale.ROOT)
    taskName.contains("release") || taskName == "build" || taskName == "assemble"
}
val signingValues = mapOf(
    "path" to secret("TOKENFLOW_KEYSTORE_PATH"),
    "storePassword" to secret("TOKENFLOW_KEYSTORE_PASSWORD"),
    "alias" to secret("TOKENFLOW_KEY_ALIAS"),
    "keyPassword" to secret("TOKENFLOW_KEY_PASSWORD"),
)
val anySigningValueSupplied = signingValues.values.any { it != null }
val allSigningValuesPresent = signingValues.values.all { !it.isNullOrBlank() }

if (fdroidBuild) {
    require(!anySigningValueSupplied) {
        "F-Droid builds must not receive any TokenFlow release signing values"
    }
} else {
    require(!anySigningValueSupplied || allSigningValuesPresent) {
        "Release signing values must be either all present and non-blank or all absent"
    }
    if (releaseRequested) {
        require(allSigningValuesPresent) {
            "Release builds require TOKENFLOW_KEYSTORE_PATH, TOKENFLOW_KEYSTORE_PASSWORD, TOKENFLOW_KEY_ALIAS, and TOKENFLOW_KEY_PASSWORD"
        }
    }
}

android {
    namespace = "xyz.mek030399.tokenflow"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "xyz.mek030399.tokenflow"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "2.5.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (allSigningValuesPresent) {
            create("release") {
                storeFile = file(signingValues.getValue("path")!!)
                storePassword = signingValues.getValue("storePassword")
                keyAlias = signingValues.getValue("alias")
                keyPassword = signingValues.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE.md")
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.ezylang:EvalEx:3.7.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.github.mwiede:jsch:2.28.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    implementation("io.ktor:ktor-client-okhttp:3.5.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jsoup:jsoup:1.23.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    implementation("org.apache.poi:poi-scratchpad:5.4.1")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyThirdPartyNotices = tasks.register<VerifyThirdPartyNotices>("verifyThirdPartyNotices") {
    group = "verification"
    description = "Verifies that third-party notices exactly cover Release runtime artifacts."
    noticesFile.set(layout.projectDirectory.file("src/main/res/raw/third_party_notices.md"))
}

configurations.configureEach {
    if (name == "releaseRuntimeClasspath") {
        val coordinates = incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { artifact ->
                val component = artifact.id.componentIdentifier
                require(component is ModuleComponentIdentifier) {
                    "Unsupported non-module Release runtime artifact: $component"
                }
                with(component) { "$group:$module:$version" }
            }
                .distinct()
                .sorted()
        }
        verifyThirdPartyNotices.configure {
            artifactCoordinates.set(coordinates)
        }
    }
}
