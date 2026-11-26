import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.ScopedArtifacts
import taen.ApkScan
import taen.PrivacyCheck
import taen.VimBytecodeCheck
import taen.WhatsNewTask

plugins {
    id("com.android.application")
    kotlin("plugin.serialization") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
}

android {
    compileSdk = 37
    buildToolsVersion = "36.0.0" // pinned, see BUILDING.md

    defaultConfig {
        applicationId = "io.github.nazandrake.taen"
        minSdk = 30
        targetSdk = 37
        versionCode = 15
        versionName = "2.0.6"
        ndk {
            abiFilters.clear()
            // only 64-bit ARM (the Pixel 8 Pro and the reference test phone). ONNX Runtime adds 24 to 41 MB per ABI; a 32-bit or x86 build
            // would need the other three again (versionCode 3 -> 4 changes the APK anyway).
            abiFilters.addAll(listOf("arm64-v8a"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            proguardFile("proguard-release.pro") // strips android.util.Log calls (typed text must not reach logcat)
        }
        create("audit") { // the release code (R8, Log calls stripped) but debuggable so run-as works; only for tools/leak-audit.py, never shipped
            initWith(getByName("release"))
            isDebuggable = true
            applicationIdSuffix = ".audit"
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isDefault = true
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    output.outputFileName = "Taen_${defaultConfig.versionName}-${variant.buildType}.apk"
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric writes a directory per test; keep it on disk (build/tmp), not in the RAM-backed /tmp
                val tmp = layout.buildDirectory.dir("tmp/test-tmp").get().asFile
                test.doFirst { tmp.mkdirs() }
                test.systemProperty("java.io.tmpdir", tmp.absolutePath)
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Argon2id for the encrypted export. Only Argon2BytesGenerator and what it needs survive R8.
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")

    // Voice typing: ONNX Runtime (MIT), CPU only. 1.27.0 is the newest release without the telemetry classes, INTERNET permission and
    // HTTP client that 1.30.0 and later add to the AAR (checked on the AARs, BUILDING.md section 8). The engine itself is plain Kotlin in
    // src/main/java/helium314/keyboard/latin/voice/engine (host tests: :voice-engine:test).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.11.01")) // newer requires minSdk 23
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors, newer requires minSdk 23

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}

// ---------------------------------------------------------------------------------------------
// Privacy gate (invariants P1, P2, P4, P6, see INVARIANTS.md). Runs locally.
// ./gradlew privacyCheck checks the merged manifest of every variant, the backup rules and the sources (task classes
// in /buildSrc), then scans the release APK (apkScanRelease: class references and native libraries).
// ---------------------------------------------------------------------------------------------
// AGP 9 only creates the debug unit test variant; the runTests variant skips the tests that are known to fail
// upstream (network access, open HeliBoard bugs), so it is the one that has to be green.
androidComponents.beforeVariants { builder ->
    if (builder.buildType == "runTests") builder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
}

// The "What's new" box: its text is the CHANGELOG.md entry of versionName, built into an asset (taen.WhatsNew, BUILDING.md). The shipped builds (release and
// the audit copy of it) fail without an entry; the others write an empty asset, which the app reads as no box.
androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val whatsNew = tasks.register<WhatsNewTask>("generateWhatsNew$capitalized") {
        description = "Writes the notes of the What's new box of ${variant.name} from CHANGELOG.md"
        changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
        versionName.set(android.defaultConfig.versionName ?: error("versionName is not set"))
        required.set(variant.buildType == "release" || variant.buildType == "audit")
        outputDir.set(layout.buildDirectory.dir("generated/whatsNew/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(whatsNew, WhatsNewTask::outputDir)
}

androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    tasks.register<PrivacyCheck>("privacyCheck$capitalized") {
        group = "verification"
        description = "Checks the merged manifest, backup rules and sources of ${variant.name} against the privacy invariants"
        manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        xmlResources.set(layout.projectDirectory.dir("src/main/res/xml"))
        sources.set(layout.projectDirectory.dir("src/main/java"))
        nativeSources.set(layout.projectDirectory.dir("src/main/jni"))
        variantName.set(variant.name)
    }
    if (variant.buildType == "release") { // the shipped build; the unminified variants still contain whole libraries
        // the Vim package is checked on the class files of the Kotlin compiler AND of javac (before R8), by every reference it makes; a class of
        // the package in any other compiled output of the program (AGP's complete class set: project, modules, libraries) fails the task
        val vimBytecode = tasks.register<VimBytecodeCheck>("vimBytecode$capitalized") {
            group = "verification"
            description = "Checks that the compiled Vim package refers only to classes and members on privacy/vim-bytecode.allow"
            classes.from(tasks.named("compile${capitalized}Kotlin").map { it.outputs.files })
            classes.from(tasks.named("compile${capitalized}JavaWithJavac").map { it.outputs.files })
            allowList.set(rootProject.layout.projectDirectory.file("privacy/vim-bytecode.allow"))
            report.set(layout.buildDirectory.file("reports/vim-bytecode-${variant.name}.txt"))
            variantName.set(variant.name)
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(vimBytecode).toGet(ScopedArtifact.CLASSES, VimBytecodeCheck::allJars, VimBytecodeCheck::allDirectories)
        tasks.register<ApkScan>("apkScan$capitalized") {
            group = "verification"
            description = "Scans the ${variant.name} APK for network, tracker and class-loader references and unlisted native libraries"
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            dexRules.set(rootProject.layout.projectDirectory.file("privacy/dex-rules.txt"))
            nativeLibs.set(rootProject.layout.projectDirectory.file("privacy/native-libs.allow"))
            variantName.set(variant.name)
        }
    }
}

tasks.register("privacyCheck") {
    group = "verification"
    description = "Runs the privacy gate for every variant, then scans the release APK"
    dependsOn(provider { tasks.names.filter { it.startsWith("privacyCheck") && it != "privacyCheck" } })
    dependsOn("apkScanRelease")
    dependsOn("vimBytecodeRelease")
}

// a release APK is never assembled without passing the gate
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn("privacyCheckRelease", "vimBytecodeRelease") }
