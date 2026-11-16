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
}
