package taen

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Writes the asset of the "What's new" box from CHANGELOG.md ([WhatsNew]); the inputs are the file and the version name, nothing else. */
@CacheableTask
abstract class WhatsNewTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val changelog: RegularFileProperty
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val required: Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun write() {
        WhatsNew.write(changelog.get().asFile, versionName.get(), required.get(), outputDir.get().asFile)
    }
}
