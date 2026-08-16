pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
         google()
        mavenCentral()
    }
}

rootProject.name = "sdl-mixer-kmp"

// sdl-kmp provides the SDL3 bindings sdl-mixer-kmp builds on; the published
// artifacts (cn.enaium.sdl:sdl-kmp:1.0.7) are resolved from the repositories
// declared above (see the `api` dependency in sdl-mixer-kmp/build.gradle.kts).

include(":sdl-mixer-kmp")

include(":examples:mixer_device")

// Per-OS/arch JNI artifacts that bundle the prebuilt libsdl_mixer_jni shared
// library as a classpath resource. The Mixer JNI library references the SDL3
// symbols exported by libsdl_jni (from the sdl-kmp project), so the matching
// sdl-kmp JNI artifact must be on the classpath too (sdl-kmp pulls it in
// automatically). MixerNativeLoader extracts the matching one at runtime.
listOf(
    "linux-x86_64",
    "linux-aarch64",
    "darwin-x86_64",
    "darwin-aarch64",
    "windows-x86_64",
).forEach { classifier ->
    val name = ":mixer-jni-jvm-$classifier"
    include(name)
    project(name).projectDir = file("jni/jvm/$classifier")
}
