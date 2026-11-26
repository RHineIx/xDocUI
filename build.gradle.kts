plugins {
    id("com.android.application") version "9.5.3" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}