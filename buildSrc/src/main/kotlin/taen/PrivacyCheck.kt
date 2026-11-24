package taen

import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Privacy gate for one variant (invariants P1, P2, P3 providers, P4, P6, P7, P10 in INVARIANTS.md): merged manifest, backup rules and sources.
 * Runs locally, no cloud CI. `./gradlew privacyCheck` runs it for every variant.
 */
abstract class PrivacyCheck : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:InputDirectory abstract val xmlResources: DirectoryProperty
    @get:InputDirectory abstract val sources: DirectoryProperty
    @get:InputDirectory abstract val nativeSources: DirectoryProperty
    @get:Input abstract val variantName: Property<String>

    private val backupDomains = listOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )
    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    private fun children(e: org.w3c.dom.Element, tag: String) =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().filter { it.tagName == tag }

    private fun checkExcludesEverything(file: File, section: org.w3c.dom.Element, problems: MutableList<String>) {
        if (children(section, "include").isNotEmpty()) problems.add("P4: <include> in ${file.name} <${section.tagName}>")
        val excluded = children(section, "exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
        val missing = backupDomains.filter { it !in excluded }
        if (missing.isNotEmpty()) problems.add("P4: ${file.name} <${section.tagName}> does not exclude domains $missing")
    }

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        val ns = "http://schemas.android.com/apk/res/android"
        val root = parse(manifest.get().asFile)

        // P1, P2: permissions
        val declared = (children(root, "uses-permission") + children(root, "uses-permission-sdk-23"))
            .map { it.getAttributeNS(ns, "name") }
        declared.filter { it in SourceRules.forbiddenPermissions }.forEach { problems.add("P1/P2: forbidden permission $it in the merged manifest") }

        // P2: no package visibility declarations, so the app cannot list or look up installed apps
        if (children(root, "queries").isNotEmpty()) problems.add("P2: the merged manifest has a <queries> element (installed-app enumeration)")

        val app = children(root, "application").single()

        // P3, P7: no content provider of our own, and none that is exported
        children(app, "provider").forEach { p ->
            val name = p.getAttributeNS(ns, "name")
            if (name !in SourceRules.allowedProviders) problems.add("P3: provider $name in the merged manifest is not on the allowlist")
            if (p.getAttributeNS(ns, "exported") != "false") problems.add("P3: provider $name must be explicitly not exported")
            val grants = p.getAttributeNS(ns, "grantUriPermissions") == "true" || children(p, "grant-uri-permission").isNotEmpty()
            if (grants && name !in SourceRules.grantingProviders) problems.add("P3: provider $name must not grant URI permissions (only the media provider does)")
            if (name in SourceRules.grantingProviders && p.getAttributeNS(ns, "grantUriPermissions") != "true") problems.add("P3: provider $name must have grantUriPermissions=true")
        }

        // P4: backup attributes
        if (app.getAttributeNS(ns, "allowBackup") != "false") problems.add("P4: android:allowBackup must be explicitly false")
        if (app.getAttributeNS(ns, "backupAgent").isNotEmpty()) problems.add("P4: android:backupAgent must not be set")
        if (app.getAttributeNS(ns, "fullBackupContent") != "@xml/backup_rules") problems.add("P4: android:fullBackupContent must be @xml/backup_rules")
        if (app.getAttributeNS(ns, "dataExtractionRules") != "@xml/data_extraction_rules") problems.add("P4: android:dataExtractionRules must be @xml/data_extraction_rules")

        // P4: the rule files exclude every domain
        val xmlDir = xmlResources.get().asFile
        val backupFile = File(xmlDir, "backup_rules.xml")
        val extractionFile = File(xmlDir, "data_extraction_rules.xml")
        if (backupFile.isFile) checkExcludesEverything(backupFile, parse(backupFile), problems)
        else problems.add("P4: $backupFile missing")
        if (extractionFile.isFile) {
            val extraction = parse(extractionFile)
            for (section in listOf("cloud-backup", "device-transfer")) {
                val e = children(extraction, section).singleOrNull()
                if (e == null) problems.add("P4: ${extractionFile.name} has no <$section>") else checkExcludesEverything(extractionFile, e, problems)
            }
        } else problems.add("P4: $extractionFile missing")

        // P1, P2, P6: sources
        sources.get().asFile.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
            problems.addAll(SourceRules.check(f.relativeTo(sources.get().asFile).invariantSeparatorsPath, f.readText()))
        }

        // P11: the native engine must not log either
        val jni = nativeSources.get().asFile
        jni.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(jni).invariantSeparatorsPath
            if (SourceRules.isNativeSource(rel)) problems.addAll(SourceRules.checkNative(rel, f.readText()))
        }

        if (problems.isNotEmpty())
            throw GradleException("privacyCheck FAILED for ${variantName.get()}:\n  " + problems.joinToString("\n  "))
        logger.lifecycle("privacyCheck OK (${variantName.get()}): ${declared.size} permissions, backup off, no <queries>, sources and native sources clean")
    }
}

/**
 * Checks the compiled classes of the Vim package against privacy/vim-bytecode.allow (see [VimBytecode]). The input is the output of the Kotlin
 * compiler, not of R8: the guard must not depend on where the shrinker moves code.
 */
abstract class VimBytecodeCheck : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val classes: ConfigurableFileCollection
    @get:InputFile abstract val allowList: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty
    @get:Input abstract val variantName: Property<String>

    @TaskAction
    fun check() {
        val found = sortedMapOf<String, ByteArray>()
        for (root in classes.files.filter { it.isDirectory }) {
            root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (rel.startsWith(VimBytecode.PACKAGE_DIR)) found[rel] = f.readBytes()
            }
        }
        val entries = VimBytecode.parse(allowList.get().asFile.readLines())
        val problems = VimBytecode.check(found, entries)
        val refs = sortedSetOf<String>()
        for (bytes in found.values) try { refs.addAll(VimBytecode.references(bytes).refs.filter { '.' !in it }) } catch (e: IllegalArgumentException) { /* reported by check */ }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(refs.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty())
            throw GradleException("vimBytecode FAILED for ${variantName.get()}:\n  " + problems.joinToString("\n  "))
        logger.lifecycle("vimBytecode OK (${variantName.get()}): ${found.size} classes, ${refs.size} referenced classes, all on the allow list")
    }
}

/** Scans the built APK of a variant with privacy/dex-rules.txt and privacy/native-libs.allow (see [ApkRules]). */
abstract class ApkScan : DefaultTask() {
    @get:InputDirectory abstract val apkDir: DirectoryProperty
    @get:InputFile abstract val dexRules: RegularFileProperty
    @get:InputFile abstract val nativeLibs: RegularFileProperty
    @get:Input abstract val variantName: Property<String>

    @TaskAction
    fun scan() {
        val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        if (apks.size != 1) throw GradleException("apkScan: expected exactly one APK in ${apkDir.get().asFile}, found ${apks.size}")
        val rules = ApkRules.parse(dexRules.get().asFile.readLines())
        val (libs, logLinking) = ApkRules.parseNativeLibs(nativeLibs.get().asFile.readLines())
        val problems = ApkRules.scanApk(apks[0], rules, libs, logLinking)
        if (problems.isNotEmpty())
            throw GradleException("apkScan FAILED for ${variantName.get()} (${apks[0].name}):\n  " + problems.joinToString("\n  "))
        logger.lifecycle("apkScan OK (${variantName.get()}): ${apks[0].name} has no denied class references; native libs match the allowlist")
    }
}
