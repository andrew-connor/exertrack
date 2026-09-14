plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

// Build output must live outside the Google Drive synced folder: Drive holds
// freshly written files open for upload, which breaks incremental builds
// ("Unable to delete directory ... build/intermediates").
allprojects {
    layout.buildDirectory.set(File("D:/gradle-builds/exertrack/$name"))
}
