/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.sdl.mixer

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Resolves and loads the JNI shared library bundled in the matching
 * `sdl-mixer-kmp-jni-jvm-{os}-{arch}` artifact's resources.
 *
 * Falls back to `System.loadLibrary("sdl_mixer_jni")` if no bundled binary
 * matches the host (a developer workflow places the lib on
 * `java.library.path`).
 *
 * The library bundles its own SDL3 (like sdl-kmp's libsdl_jni); [Jni] still
 * initializes sdl-kmp first so the SDL3 bindings are ready when the mixer
 * library is used.
 */
internal object MixerNativeLoader {
    private const val LIB_NAME = "sdl_mixer_jni"
    private const val RESOURCE_BASE = "/cn/enaium/sdl/mixer/native"

    fun load() {
        val classifier = detectClassifier()
        // Windows DLLs don't use the "lib" prefix (sdl_mixer_jni.dll), while
        // Unix shared libraries do (libsdl_mixer_jni.so/.dylib).
        val prefix = if (classifier.startsWith("windows")) "" else "lib"
        val libFile = "$prefix$LIB_NAME.${libExtension()}"
        val resourcePath = "$RESOURCE_BASE/$classifier/$libFile"
        val stream = MixerNativeLoader::class.java.getResourceAsStream(resourcePath)
        if (stream == null) {
            val found = MixerNativeLoader::class.java.classLoader
                ?.getResources("cn/enaium/sdl/mixer/native")
                ?.toList()
                ?.flatMap { url ->
                    runCatching {
                        (url.openConnection() as java.net.JarURLConnection)
                            .jarFile.entries().toList().map { it.name }
                    }.getOrDefault(emptyList())
                }
                ?.filter { it.contains("native/") }
            throw UnsatisfiedLinkError(
                "Resource $resourcePath not found. " +
                    "os=${System.getProperty("os.name")}, arch=${System.getProperty("os.arch")}, " +
                    "classifier=$classifier. Native resources on classpath: ${found ?: "n/a"}",
            )
        }
        val bytes = stream.use { it.readBytes() }
        val target = extractToTemp(bytes, libFile)
        try {
            System.load(target.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "Failed to load $libFile from $resourcePath (os=${System.getProperty("os.name")}, " +
                    "arch=${System.getProperty("os.arch")}): ${e.message}. " +
                    "Make sure the sdl-kmp JVM artifact (libsdl_jni) loads first.",
            )
        }
    }

    private fun extractToTemp(bytes: ByteArray, libFile: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }.take(16)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "sdl-mixer-kmp-$hex")
        tempDir.mkdirs()
        val target = File(tempDir, libFile)
        if (!target.isFile || target.length().toInt() != bytes.size) {
            val tmp = File.createTempFile(libFile, ".part", tempDir)
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    private fun detectClassifier(): String {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val osArch = System.getProperty("os.arch").orEmpty().lowercase()
        val os = when {
            osName.contains("linux") -> "linux"
            osName.contains("mac") || osName.contains("darwin") || osName.contains("osx") -> "darwin"
            osName.contains("win") -> "windows"
            else -> error("Unsupported OS for sdl-mixer-kmp JVM artifact: $osName")
        }
        val arch = when (osArch) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> error("Unsupported CPU architecture for sdl-mixer-kmp JVM artifact: $osArch")
        }
        return "$os-$arch"
    }

    private fun libExtension(): String {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            osName.contains("win") -> "dll"
            osName.contains("mac") || osName.contains("darwin") || osName.contains("osx") -> "dylib"
            else -> "so"
        }
    }
}
