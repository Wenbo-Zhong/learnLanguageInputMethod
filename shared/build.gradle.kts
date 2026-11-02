plugins { kotlin("multiplatform"); id("com.android.library") }
kotlin {
    androidTarget()
    jvm()
    iosArm64 { binaries.framework { baseName = "Shared"; isStatic = true } }
    iosSimulatorArm64 { binaries.framework { baseName = "Shared"; isStatic = true } }
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
        commonTest.dependencies { implementation(kotlin("test")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") }
    }
}
android { namespace = "dev.learnime.shared"; compileSdk = 35; defaultConfig { minSdk = 26 } }
