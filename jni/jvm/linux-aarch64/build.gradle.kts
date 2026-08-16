/*
 * Per-OS/arch JNI artifact: linux-aarch64.
 * Ships libsdl_mixer_jni.so as a classpath resource at
 * /cn/enaium/sdl/mixer/native/linux-aarch64/, which MixerNativeLoader
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

val jniOs = "linux"
val jniArch = "aarch64"
val classifier = "$jniOs-$jniArch"
val libFile = "libsdl_mixer_jni.so"
val resourceDir = "cn/enaium/sdl/mixer/native/$classifier"

val host = OperatingSystem.current()
val hostArch = System.getProperty("os.arch").lowercase()
val hostIsLinuxArm64 = host.isLinux && (hostArch == "aarch64" || hostArch == "arm64")
val hostIsLinuxX64 = host.isLinux && (hostArch == "amd64" || hostArch == "x86_64")

fun hasAarch64CrossToolchain(): Boolean {
    val name = "aarch64-linux-gnu-gcc"
    return System.getenv("PATH")?.split(File.pathSeparator).orEmpty().any { dir ->
        val f = File(dir, name)
        f.isFile && f.canExecute()
    }
}

val canBuildHere = hostIsLinuxArm64 || (hostIsLinuxX64 && hasAarch64CrossToolchain())

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
    val args = mutableListOf(
        cmakeExecutable,
        rootProject.file("jni").absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJNI_INCLUDE_DIR=$jniInclude",
        "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/linux",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
    )
    if (hostIsLinuxX64) {
        // Cross-compile with the aarch64-linux-gnu toolchain from an x86_64
        // host (the SDL3 dlopen-based drivers only need the arch-agnostic
        // headers from /usr/include, so no multiarch sysroot is required).
        args += listOf(
            "-DCMAKE_SYSTEM_NAME=Linux",
            "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
            "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
            "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++",
        )
    }
    commandLine(args)
}

val buildJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libsdl_mixer_jni.so for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/jni_mixer.cpp"),
    )
    inputs.dir(rootProject.file("includes/SDL_mixer"))
    inputs.dir(rootProject.file("includes/SDL"))
    outputs.file(nativeOutputDir.map { it.file(libFile) })
}

tasks.named<Copy>("processResources") {
    dependsOn(buildJniLibrary)
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
