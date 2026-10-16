plugins {
    id("com.android.application") version "7.0.4" apply false
    id("org.jetbrains.kotlin.android") version "1.5.21" apply false
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
