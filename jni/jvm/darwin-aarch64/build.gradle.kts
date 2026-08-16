/*
 * Per-OS/arch JNI artifact: darwin-aarch64.
 * Ships libsdl_mixer_jni.dylib as a classpath resource at
 * /cn/enaium/sdl/mixer/native/darwin-aarch64/, which MixerNativeLoader
 * (in :sdl-mixer-kmp's jvmMain) extracts and System.load()s at runtime.
 *
 * The library references SDL3 symbols resolved from libsdl_jni (shipped by
 * the sdl-kmp project), which the sdl-kmp JVM artifact loads first.
 */
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Resolves cmake to an absolute path (searches PATH, well-known install
// locations, then the Android SDK's bundled cmake). The Exec tasks must not
// rely on PATH lookup, since the Gradle daemon's environment may differ from
// an interactive shell.
fun resolveCmakeExecutable(): String {
    val exeName = if (OperatingSystem.current().isWindows) "cmake.exe" else "cmake"

    System.getenv("PATH")?.split(File.pathSeparator).orEmpty().forEach { dir ->
        val candidate = File(dir, exeName)
        if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
    }

    val extraPaths = listOf(
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/usr/bin",
        "/opt/local/bin",
    )
    extraPaths.forEach { dir ->
        val candidate = File(dir, exeName)
        if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
    }

    return exeName
}

val cmakeExecutable: String by lazy { resolveCmakeExecutable() }

val jniOs = "darwin"
val jniArch = "aarch64"
val classifier = "$jniOs-$jniArch"
val libFile = "libsdl_mixer_jni.dylib"
val resourceDir = "cn/enaium/sdl/mixer/native/$classifier"

val canBuildHere = OperatingSystem.current().isMacOsX

val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "cmake-configures libsdl_mixer_jni for $classifier."
    onlyIf { canBuildHere }
    val outDir = nativeOutputDir.get().asFile
    val buildDir = cmakeBuildDir.get().asFile
    doFirst {
        outDir.mkdirs()
        buildDir.mkdirs()
    }
    workingDir = buildDir
    val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME") ?: ""
    val jniInclude = if (javaHome.isNotEmpty()) "$javaHome/include" else ""
    commandLine(
        cmakeExecutable,
        rootProject.file("jni").absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJNI_INCLUDE_DIR=$jniInclude",
        "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/darwin",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
    )
}

val buildJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libsdl_mixer_jni.dylib for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/jni_mixer.cpp"),
        rootProject.file("jni/sdl_mixer_cpu_compat.c"),
    )
    inputs.dir(rootProject.file("includes/SDL_mixer"))
    inputs.dir(rootProject.file("includes/SDL"))
    outputs.file(nativeOutputDir.map { it.file(libFile) })
}

tasks.named<Copy>("processResources") {
    dependsOn(buildJniLibrary)
    // Use the build task's declared outputs (lazily resolved at execution
    // time) instead of the directory Provider, which may be snapshotted
    // empty at configuration time.
    from(buildJniLibrary.map { it.outputs.files }) {
        include(libFile)
        into(resourceDir)
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "sdl-mixer-kmp-jni-jvm-$classifier",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("sdl-mixer-kmp-jni-jvm-$classifier")
        description.set(
            "Prebuilt JNI shared library for sdl-mixer-kmp on $jniOs/$jniArch. " +
                "Loaded automatically by MixerNativeLoader; not intended to be depended on directly.",
        )
        url.set("https://github.com/Enaium/sdl-mixer-kmp")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("Zlib")
                url.set("https://opensource.org/license/zlib")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Enaium")
            }
        }
        scm {
            url.set("https://github.com/Enaium/sdl-mixer-kmp")
            connection.set("scm:git:git@github.com:Enaium/sdl-mixer-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/sdl-mixer-kmp.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/sdl-mixer-kmp/issues")
        }
    }
}
