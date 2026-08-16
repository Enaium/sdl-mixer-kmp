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

package cn.enaium.sdl.example.mixer

import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLAudioDeviceID
import cn.enaium.sdl.SDLAudioFormat
import cn.enaium.sdl.SDLAudioSpec
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLProperties
import cn.enaium.sdl.mixer.SDLAudio
import cn.enaium.sdl.mixer.SDLMixer
import cn.enaium.sdl.mixer.SDLMixerDevice
import cn.enaium.sdl.mixer.SDLMixerPlayProp
import cn.enaium.sdl.mixer.SDLTrack

/**
 * The SDL_mixer demo, shared by every platform.
 *
 * It exercises the sdl-mixer-kmp bindings:
 *
 *  - [SDLMixer.createMixerDevice] playing to the default audio device, with a
 *    fallback to a memory mixer ([SDLMixer.createMixer] + [SDLMixerDevice.generate])
 *    when no audio device is available (headless CI, servers, ...);
 *  - audio loaded from a file (optional CLI argument) and generated in code
 *    ([SDLMixerDevice.createSineWaveAudio]);
 *  - tracks with tags, loop/fade playback options through [SDLProperties]
 *    ([SDLMixerPlayProp]), gain and frequency-ratio control, pause/resume and
 *    fading stop;
 *  - callbacks: track stopped, and a post-mix callback with the final float
 *    samples.
 *
 * A fixed timeline is driven by [seconds]; it ends with a clean shutdown.
 */
class MixerDemo(
    private val mixer: SDLMixerDevice,
    private val audio: SDLAudio,
    private val memory: Boolean,
    private val seconds: Int,
) {
    private var music: SDLTrack? = null
    private var sfx: SDLTrack? = null
    private var postMixFrames = 0
    private var stoppedCount = 0

    init {
        println(
            "mixer: ${if (memory) "memory (offline generation)" else "device"}  " +
                "format=${mixer.format}",
        )

        // Fire-and-forget callback style: post-mix sees the final mixed output.
        mixer.setPostMixCallback { _, spec, pcm ->
            postMixFrames += pcm.size / (spec.channels * (if (spec.format == SDLAudioFormat.F32) 1 else 2))
        }

        // "Music" track: loops forever.
        music = mixer.createTrack().also { track ->
            track.setAudio(audio)
            track.tag("music")
            val options = SDLProperties.create()
            SDLProperties.setProperty(options, SDLMixerPlayProp.LOOPS_NUMBER, -1L)
            SDLProperties.setProperty(options, SDLMixerPlayProp.FADE_IN_MILLISECONDS_NUMBER, 800L)
            check(track.play(options)) { "music play failed: ${SDLMixer.error()}" }
            println("music track playing (loops=infinite, fade-in=800ms)")
        }

        // "SFX" track: plays the audio once (restarting at each event).
        sfx = mixer.createTrack().also { track ->
            track.setAudio(audio)
            track.tag("sfx")
            track.setStoppedCallback { stoppedCount++ }
            check(track.play()) { "sfx play failed: ${SDLMixer.error()}" }
            println("sfx track playing (once)")
        }
    }

    /** Waits [ms] milliseconds of mixer time (generates offline for memory mixers). */
    private fun sleep(ms: Int) {
        if (!memory) {
            SDL.delay(ms)
            return
        }
        // A memory mixer only advances when generate() is called; mix in
        // ~20ms chunks so the timeline stays close to wall-clock time.
        val spec = mixer.format
        val chunkBytes = if (spec != null) {
            val frameBytes = if (spec.format == SDLAudioFormat.F32) spec.channels * 4 else spec.channels * 2
            spec.freq * frameBytes * 20 / 1000
        } else 4800 * 4
        val totalBytes = chunkBytes * ms / 20
        val buffer = ByteArray(chunkBytes)
        var mixed = totalBytes
        while (mixed > 0) {
            mixer.generate(buffer)
            mixed -= chunkBytes
        }
    }

    /** Runs the timeline; returns `false` when the demo should stop. */
    fun run(): Boolean {
        val timeline = listOf(
            0 to "play",
            2 to "sfx fade out (200ms)",
            3 to "pause all",
            4 to "resume all",
            5 to "stop all (fade 500ms)",
            6 to "play again, master gain 0.5",
            7 to "master gain 1.0, frequency ratio 1.2",
            8 to "stop everything",
        )

        for ((at, action) in timeline) {
            sleep((at * 1000 - elapsed()).toInt().coerceAtLeast(0))
            when (action) {
                "play" -> {}
                "sfx fade out (200ms)" -> {
                    sfx?.stop(200)
                }
                "pause all" -> {
                    mixer.pauseAllTracks()
                }
                "resume all" -> {
                    mixer.resumeAllTracks()
                }
                "stop all (fade 500ms)" -> {
                    mixer.stopAllTracks(500)
                }
                "play again, master gain 0.5" -> {
                    mixer.gain = 0.5f
                    music?.play()
                }
                "master gain 1.0, frequency ratio 1.2" -> {
                    mixer.gain = 1.0f
                    mixer.frequencyRatio = 1.2f
                    println("mixer gain=${mixer.gain} frequencyRatio=${mixer.frequencyRatio}")
                }
                "stop everything" -> {
                    mixer.stopAllTracks(300)
                }
            }
            println("t=${elapsed() / 1000.0}s $action (music playing=${music?.playing}, sfx playing=${sfx?.playing})")
        }

        sleep((seconds * 1000 - elapsed()).toInt())
        println("stopped callback fired $stoppedCount times, post-mix callback saw $postMixFrames frames")
        return false
    }

    private fun elapsed(): Long = (SDL.getTicks() - start).toLong()

    private val start = SDL.getTicks()

    /** Releases all demo resources. */
    fun close() {
        music?.close()
        sfx?.close()
        audio.close()
        mixer.close()
    }
}

/**
 * Sets up the demo: SDL audio init, mixer (device with a memory fallback),
 * audio and tracks. Returns null when mixer creation fails.
 */
fun createMixerDemo(audioPath: String?, seconds: Int): MixerDemo? {
    val device = runCatching {
        SDLMixer.createMixerDevice(SDLAudioDeviceID.DEFAULT_PLAYBACK)
    }.getOrNull()
    val (mixer, isMemory) = if (device != null) {
        device to false
    } else {
        SDLMixer.createMixer(SDLAudioSpec(SDLAudioFormat.F32, 2, 48000)) to true
    }
    if (isMemory) {
        println("no audio device available — using an offline memory mixer")
    }

    // The audio: an optional file (WAV/MP3/FLAC/OGG/...), or a 440Hz sine wave.
    val audio = if (audioPath != null) {
        try {
            mixer.loadAudio(audioPath, predecode = false)
        } catch (t: Throwable) {
            println("failed to load $audioPath (${t.message}) — using a sine wave instead")
            mixer.createSineWaveAudio(440, 0.4f, 6000)
        }
    } else {
        mixer.createSineWaveAudio(440, 0.4f, 6000)
    }
    println("audio: duration=${audio.duration} frames format=${audio.format}")

    return try {
        MixerDemo(mixer, audio, isMemory, seconds)
    } catch (t: Throwable) {
        println("demo setup failed: ${t.message}")
        audio.close()
        mixer.close()
        null
    }
}

/**
 * Blocking runner used by native / JVM: initializes SDL audio + SDL_mixer,
 * creates the demo and runs its timeline.
 */
fun runExample(audioPath: String?, seconds: Int) {
    SDL.setMainReady()

    if (!SDL.init(SDLInitFlags.AUDIO)) {
        error("SDL_Init(AUDIO) failed: ${SDL.error()}")
    }

    println("SDL ${SDL.version()} (${SDL.revision()})")
    println("Audio driver: ${SDL.getCurrentAudioDriver()}")

    if (!SDLMixer.init()) {
        error("MIX_Init failed: ${SDLMixer.error()}")
    }
    println("SDL_mixer ${SDLMixer.version()}")
    val decoders = (0 until SDLMixer.numAudioDecoders()).mapNotNull { SDLMixer.audioDecoder(it) }
    println("audio decoders: ${decoders.joinToString(", ")}")

    val demo = createMixerDemo(audioPath, seconds) ?: run {
        SDLMixer.quit()
        SDL.quit()
        return
    }

    demo.run()
    demo.close()
    SDLMixer.quit()
    SDL.quit()
    println("done")
}
