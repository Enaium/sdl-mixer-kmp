# sdl-mixer-kmp

Kotlin Multiplatform bindings for [SDL_mixer 3](https://github.com/libsdl-org/SDL_mixer) (audio mixing with file decoding), built on top of [sdl-kmp](https://github.com/Enaium/sdl-kmp). The public API lives in the `cn.enaium.sdl.mixer` package and works directly with the sdl-kmp types (`SDLAudioSpec`, `SDLIOStream`, `SDLProperties`, ...).

Two implementations, mirroring sdl-kmp and sdl-ttf-kmp:

- **JVM**: SDL3 and SDL_mixer (with the decoders bundled in this repository's `SDL_mixer` submodule: WAV/AIFF/VOC/AU, FLAC via dr_flac, MP3 via dr_mp3, Ogg Vorbis via stb_vorbis and MIDI via timidity) are compiled by CMake (`jni/`) into a JNI shared library (`libsdl_mixer_jni`), shipped as per-OS/arch `sdl-mixer-kmp-jni-jvm-*` artifacts — the same self-contained approach as sdl-kmp's `libsdl_jni`. `MixerNativeLoader` extracts the matching binary at runtime. The process contains a second SDL3 copy; SDL_mixer errors are read through the mixer-side `SDL_GetError` (`SDLMixer.error()`).
- **Native (Kotlin/Native)**: the SDL_mixer static library (including the decoders) is compiled per target with CMake and **embedded into the published klib**. SDL3 itself is not compiled: the SDL3 symbols are resolved at the consumer's final link from the sdl-kmp klib, which is always present because the bindings use the `cn.enaium.sdl` types.

## Supported platforms

| Platform | Targets                                             | Implementation                                  |
|----------|-----------------------------------------------------|-------------------------------------------------|
| JVM      | `jvm` (Linux/macOS/Windows)                         | JNI shared library (`libsdl_mixer_jni`), SDL3 + SDL_mixer compiled from source |
| macOS    | `macosArm64`, `macosX64`                            | cinterop + embedded static SDL_mixer            |
| Linux    | `linuxX64`, `linuxArm64`                            | cinterop + embedded static SDL_mixer            |
| Windows  | `mingwX64`                                          | cinterop + embedded static SDL_mixer            |
| iOS      | `iosArm64`, `iosX64`, `iosSimulatorArm64`           | cinterop + embedded static SDL_mixer            |
| tvOS     | `tvosArm64`, `tvosSimulatorArm64`                   | cinterop + embedded static SDL_mixer            |
| Android  | `androidNativeArm64`, `androidNativeArm32`, `androidNativeX64`, `androidNativeX86` | cinterop + embedded static SDL_mixer (built with the NDK) |

The bundled SDL_mixer is configured to build only the decoders implemented in
its own source tree (WAV/AIFF/VOC/AU, dr_flac, dr_mp3, stb_vorbis, timidity).
Formats that need SDL_mixer's external submodules (libogg/libvorbis/libopus,
libmpg123, FluidSynth, game-music-emu, libxmp, WavPack) are disabled so the
build is self-contained.

## Usage

The published version requires [sdl-kmp](https://github.com/Enaium/sdl-kmp) `1.0.7` (it is an `api` dependency, pulled in automatically).

`build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("cn.enaium.sdl:sdl-mixer-kmp:1.0.0")
        }
    }
}
```

```kotlin
import cn.enaium.sdl.*
import cn.enaium.sdl.mixer.*

fun main() {
    SDL.setMainReady()

    if (!SDL.init(SDLInitFlags.AUDIO)) {
        error("SDL_Init failed: ${SDL.error()}")
    }
    if (!SDLMixer.init()) {
        error("MIX_Init failed: ${SDLMixer.error()}")
    }

    // A mixer that plays directly to the default audio device.
    SDLMixer.createMixerDevice(SDLAudioDeviceID.DEFAULT_PLAYBACK).use { mixer ->
        val audio = mixer.loadAudio("/path/to/sound.wav")

        // A track: one reusable slider on the mixer board.
        val track = mixer.createTrack()
        track.setAudio(audio)
        track.tag("sfx")

        // Loop forever with an 800ms fade-in (properties from SDLMixerPlayProp).
        val options = SDLProperties.create()
        SDLProperties.setProperty(options, SDLMixerPlayProp.LOOPS_NUMBER, -1L)
        SDLProperties.setProperty(options, SDLMixerPlayProp.FADE_IN_MILLISECONDS_NUMBER, 800L)
        track.play(options)

        SDL.delay(5000)

        track.stop()  // or mixer.stopAllTracks()
        track.close()
        audio.close()
    }

    SDLMixer.quit()
    SDL.quit()
}
```

### API overview

- **Initialization**: `SDLMixer.init` / `quit`, `version`, `numAudioDecoders` / `audioDecoder`.
- **Mixers**: `createMixerDevice` plays to an audio device; `createMixer` renders to a memory buffer via `SDLMixerDevice.generate` (usable without any audio hardware — headless CI, servers, ...). Both expose master `gain`, `frequencyRatio`, `format`, locking, and tag/`pause`/`resume`/`stop` operations affecting all tracks.
- **Audio**: `loadAudio` / `loadAudioIO` (from a `cn.enaium.sdl.SDLIOStream`) / `loadRawAudio` / `createSineWaveAudio` return `SDLAudio`s that can be played on any track of any mixer (they are shared, reference-counted objects); `duration`, `format` and the metadata `properties` (see `SDLMixerMetadata`) are exposed.
- **Tracks**: assign an input with `setAudio` / `setAudioStream` (raw `SDL_AudioStream` handle) / `setIOStream`, then `play` with loop/fade/seek options built from `SDLMixerPlayProp` properties. Per-track gain, frequency ratio, loops, playback position, tags, stereo/3D positioning, output channel maps and groups are all supported.
- **Callbacks**: track stopped (`setStoppedCallback`), raw and cooked track mixing (`setRawCallback` / `setCookedCallback`), group post-mix (`SDLMixerGroup.setPostMixCallback`) and mixer post-mix (`setPostMixCallback`) — the PCM callbacks receive a **copy** of the float samples together with their `SDLAudioSpec`.
- **Decoding without a mixer**: `createAudioDecoder` / `createAudioDecoderIO` + `SDLAudioDecoder.decode`.
- **Errors**: every function either returns null/false or throws; the last error is available via `SDLMixer.error()`.

### Platform notes

- **Kotlin version compatibility**: the published klibs are built with Kotlin 2.4.x. Keep the consumer's Kotlin version in sync (the same rule applies to sdl-kmp).
- **macOS JVM**: requires `-XstartOnFirstThread` (the example `jvmRun` task already sets it).
- **JVM native library**: the matching `sdl-mixer-kmp-jni-jvm-{os}-{arch}` artifact is a transitive runtime dependency; `MixerNativeLoader` extracts `libsdl_mixer_jni` and `System.load()`s it. `libsdl_mixer_jni` bundles its own SDL3, so no `java.library.path` setup is needed.
- **Android**: building an `androidNative*` target requires an installed Android NDK (found under `$ANDROID_HOME/ndk`); the SDL_mixer static library is cross-compiled with its CMake toolchain.
- **Headless / CI**: set `SDL_VIDEO_DRIVER=dummy` to run without a display. Without audio hardware, `createMixerDevice` may fail — `createMixer` (offline generation) always works, which is what the tests and the headless example use.

## Examples

- **`examples/mixer_device`** — a mixer demo: plays to the default audio device with an automatic fallback to an offline memory mixer, drives a fixed timeline (loop playback with fade-in, track tags, pause/resume, fading stop, master gain/frequency-ratio changes) and demonstrates the stopped and post-mix callbacks. Takes an optional audio file path as the first argument (any format the bundled decoders support; defaults to a generated 440Hz sine wave) and an optional duration in seconds as the second:

```bash
# headless (CI / servers; falls back to the offline memory mixer)
SDL_VIDEO_DRIVER=dummy ./gradlew :examples:mixer_device:jvmRun
SDL_VIDEO_DRIVER=dummy ./gradlew :examples:mixer_device:runDebugExecutableMacosArm64

# with a real audio device and a file
./gradlew :examples:mixer_device:jvmRun --args="song.mp3"
```

## Building from source

Requirements: JDK 21, CMake, a C/C++ compiler; Xcode for Apple targets, the `x86_64-w64-mingw32-gcc` toolchain for MinGW cross-compiles (Linux host), the Android NDK for `androidNative*`.

```bash
git clone --recurse-submodules git@github.com:Enaium/sdl-mixer-kmp.git
cd sdl-mixer-kmp

# compile + test the JVM target
./gradlew :sdl-mixer-kmp:jvmTest

# run the example headless
SDL_VIDEO_DRIVER=dummy ./gradlew :examples:mixer_device:jvmRun

# publish everything buildable on this host to Maven Local
./gradlew :sdl-mixer-kmp:publishToMavenLocal :mixer-jni-jvm-darwin-aarch64:publishToMavenLocal
```

## CI

Both workflows are **manually triggered** (Actions tab):

- **`test.yml`** — local Maven publish + test: publishes every artifact the runner can build to Maven Local (no signing, no secrets), runs the JVM/native tests and the example headless. Use this to verify a change before publishing.
- **`publish.yml`** — formal Maven Central release: publishes the metadata + JVM module, all target klibs and the JNI artifacts to Maven Central, signed with PGP. The version is fixed at `1.0.0` (build.gradle.kts). Requires the repository secrets `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_KEY_ID` and `SIGNING_PASSWORD`.

## License

MIT. The bundled SDL3 and SDL_mixer submodules are licensed under the [zlib license](https://github.com/libsdl-org/SDL/blob/main/LICENSE.txt).
