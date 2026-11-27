// Local build logic of Taen: the privacy gate (privacyCheck, apkScan). Plain Kotlin so it runs on any OS with a JDK.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // WhatsNewTest reads the app's CHANGELOG.md and the versionName in app/build.gradle.kts: a change of either must run the tests again,
    // not reuse an earlier pass
    inputs.file(layout.projectDirectory.file("../CHANGELOG.md")).withPropertyName("changelog").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(layout.projectDirectory.file("../app/build.gradle.kts")).withPropertyName("appBuildScript").withPathSensitivity(PathSensitivity.NONE)
}
