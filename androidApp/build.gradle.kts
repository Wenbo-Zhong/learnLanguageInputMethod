plugins { id("com.android.application"); kotlin("android") }

val opusModelDir = rootProject.layout.projectDirectory.dir("models/opus-mt-tiny-zho-eng")
val m2mModelDir = rootProject.layout.projectDirectory.dir("models/m2m100-418m-int8")
val bundledAssets = layout.buildDirectory.dir("generated/bundledModelAssets")
val inputAssets = layout.buildDirectory.dir("generated/libimeAssets")
val extrasAssets = layout.buildDirectory.dir("generated/extrasAssets")
val prepareExtras by tasks.registering(Sync::class) {
    from(rootProject.file("input-data/extras"))
    from(rootProject.file("third_party/zinnia/COPYING")) { rename { "zinnia-LICENSE.txt" } }
    into(extrasAssets.map { it.dir("extras") })
    doFirst {
        check(listOf("english-frequency.txt", "STCharacters.txt", "STPhrases.txt",
            "handwriting-zh_CN.model", "vosk-cn/am/final.mdl", "NOTICE.txt").all {
            rootProject.file("input-data/extras/$it").isFile
        }) { "Run scripts/prepare-ime-extras.py and scripts/prepare-vosk-model.py before building" }
    }
}
val prepareLibimeAssets by tasks.registering(Sync::class) {
    from(rootProject.file("input-data/libime"))
    into(inputAssets.map { it.dir("libime") })
    doFirst {
        check(listOf("sc.dict", "sc.lm", "sc.lm.predict").all {
            rootProject.file("input-data/libime/$it").isFile
        }) { "Run bash scripts/prepare-libime-data.sh before building the APK" }
    }
}
val prepareBundledModel by tasks.registering(Sync::class) {
    inputs.dir(opusModelDir)
    from(opusModelDir)
    into(bundledAssets.map { it.dir("models/opus-mt-tiny-zho-eng") })
    doFirst {
        check(opusModelDir.file("model.tflite").asFile.isFile && opusModelDir.file("source.spm").asFile.isFile && opusModelDir.file("target.spm").asFile.isFile) {
            "Run bash scripts/download-opus-model.sh before building the APK"
        }
    }
}
val prepareM2mModel by tasks.registering(Sync::class) {
    inputs.dir(m2mModelDir)
    from(m2mModelDir) {
        include("onnx/encoder_model_quantized.onnx", "onnx/decoder_model_optimized.onnx",
            "model-identity.txt", "optimized-decoder-runtime.txt", "sentencepiece.bpe.model",
            "tokenizer-map.bin", "config.json", "MODEL-SOURCE.md")
    }
    into(bundledAssets.map { it.dir("models/m2m100-418m-int8") })
    doFirst {
        check(listOf("onnx/encoder_model_quantized.onnx", "onnx/decoder_model_optimized.onnx",
            "model-identity.txt", "optimized-decoder-runtime.txt", "sentencepiece.bpe.model",
            "tokenizer-map.bin").all { m2mModelDir.file(it).asFile.isFile }) {
            "Run scripts/download-m2m-model.sh with Python sentencepiece, onnx and onnxruntime==1.23.2 before building the APK"
        }
    }
}
android {
    namespace = "dev.learnime.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.learnime.android"
        minSdk = 26; targetSdk = 35; versionCode = 11; versionName = "0.6.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
    }
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("../native/CMakeLists.txt") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").assets.srcDir(bundledAssets)
    sourceSets.getByName("main").assets.srcDir(inputAssets)
    sourceSets.getByName("main").assets.srcDir(extrasAssets)
    androidResources { noCompress += listOf("tflite", "spm", "onnx", "model", "bin", "dict", "lm", "predict") }
}
tasks.named("preBuild") { dependsOn(prepareBundledModel, prepareM2mModel, prepareLibimeAssets, prepareExtras) }
dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
}
