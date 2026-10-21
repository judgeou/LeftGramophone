import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("com.android.built-in-kotlin")
}

// Use matching CI-built JNI libraries when building without a local NDK.
val prebuiltNativeLibs = providers.gradleProperty("hificorePrebuiltDir").orNull
    ?.let { rootProject.file(it) }
if (prebuiltNativeLibs != null) {
    require(prebuiltNativeLibs.isDirectory && prebuiltNativeLibs.walkTopDown()
        .any { it.name == "libhificore.so" }) {
        "hificorePrebuiltDir must contain ABI directories with libhificore.so"
    }
}

android {
    namespace = "org.nift4.gramophone.hificore"
    compileSdk = 37

    defaultConfig {
        minSdk = 23

        consumerProguardFiles("consumer-rules.pro")
        if (prebuiltNativeLibs == null) {
            externalNativeBuild {
                cmake {
                    cppFlags("")
                }
            }
        }
    }

    lint {
        lintConfig = file("../app/lint.xml")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    if (prebuiltNativeLibs == null) {
        externalNativeBuild {
            cmake {
                path("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        // Use the base source-set type with the project's legacy Android DSL.
        (this as com.android.build.gradle.BaseExtension).sourceSets
            .getByName("main").jniLibs.srcDir(prebuiltNativeLibs)
        // The CI libraries are already stripped; avoid invoking NDK strip tools.
        packaging.jniLibs.keepDebugSymbols += "**/*.so"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        prefab = prebuiltNativeLibs == null
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs = listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
            "-Xannotation-default-target=param-property", // can remove later
            "-Xstring-concat=inline", // https://issuetracker.google.com/issues/250197571#comment29
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("io.github.nift4.dlfunc:dlfunc:0.1.6")
    implementation(project(":misc:audiofxfwd"))
    // stub project that provides hidden SDK classes, which themselves depend on public SDK
    compileOnly(project(":misc:audiofxstub2"))
}
