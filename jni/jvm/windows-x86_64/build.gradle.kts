/*
 * Per-OS/arch JNI artifact: windows-x86_64.
 * Ships sdl_mixer_jni.dll as a classpath resource at
 * /cn/enaium/sdl/mixer/native/windows-x86_64/, which MixerNativeLoader
 * (in :sdl-mixer-kmp's jvmMain) extracts and System.load()s at runtime.
 *
 * The library references SDL3 symbols resolved from libsdl_jni.dll (shipped
 * by the sdl-kmp project, which exports the SDL3 symbols), so libsdl_jni.dll
 * must be loaded first.
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

val jniOs = "windows"
val jniArch = "x86_64"
val classifier = "$jniOs-$jniArch"
val libFile = "sdl_mixer_jni.dll"
val resourceDir = "cn/enaium/sdl/mixer/native/$classifier"

// The DLL is built natively on Windows hosts only (MinGW). Other hosts still
// get the artifact for dependency resolution, but the JAR ships without the
// DLL; the CI publish-windows job produces the real one.
val host = OperatingSystem.current()
val hostArch = System.getProperty("os.arch").lowercase()
val hostIsWindowsX64 = host.isWindows && (hostArch == "amd64" || hostArch == "x86_64")
val canBuildHere = hostIsWindowsX64

val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "cmake-configures sdl_mixer_jni for $classifier."
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
    val makeGenerator = if (System.getenv("MSYSTEM") != null) "MSYS Makefiles" else "MinGW Makefiles"
    val args = mutableListOf(
        cmakeExecutable,
        rootProject.file("jni").absolutePath,
        "-G", makeGenerator,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJNI_INCLUDE_DIR=$jniInclude",
        "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/win32",
        // DLLs are RUNTIME outputs in CMake, not LIBRARY outputs.
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${outDir.absolutePath}",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
        // Statically link the MinGW runtime so the DLL has no dependency on
        // libstdc++-6.dll / libgcc_s_seh-1.dll, which are not on the JVM's
        // PATH.
        "-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc -static-libstdc++",
    )
    commandLine(args)
}

val buildJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds sdl_mixer_jni.dll for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/jni_mixer.cpp"),
        rootProject.file("jni/sdl_mixer_cpu_compat.c"),
        rootProject.file("jni/sdl_windows_project_include.cmake"),
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
