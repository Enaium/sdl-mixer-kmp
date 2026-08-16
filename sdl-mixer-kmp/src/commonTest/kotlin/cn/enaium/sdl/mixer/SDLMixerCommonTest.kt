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

import cn.enaium.sdl.SDLAudioFormat
import cn.enaium.sdl.SDLAudioSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SDLMixerCommonTest {

    @Test
    fun initAndVersion() {
        assertTrue(SDLMixer.init(), "MIX_Init failed: ${SDLMixer.error()}")
        assertTrue(SDLMixer.version() >= 3000000, "expected SDL_mixer 3.x, got ${SDLMixer.version()}")
        assertTrue(SDLMixer.numAudioDecoders() > 0, "no audio decoders available")

        val decoders = (0 until SDLMixer.numAudioDecoders()).map { SDLMixer.audioDecoder(it) }
        assertTrue(decoders.any { !it.isNullOrEmpty() }, "decoder names are empty: $decoders")

        SDLMixer.quit()
    }

    @Test
    fun sineWaveAndGenerate() {
        assertTrue(SDLMixer.init(), "MIX_Init failed: ${SDLMixer.error()}")
        val mixer = SDLMixer.createMixer(SDLAudioSpec(SDLAudioFormat.F32, 2, 48000))
        assertEquals(48000, mixer.format?.freq, "mixer format: ${mixer.format}")

        // A 440 Hz sine wave, 1 second long.
        val audio = mixer.createSineWaveAudio(440, 0.5f, 1000)
        assertEquals(48000L, audio.duration, "sine wave duration")
        assertEquals(48000L, audio.msToFrames(1000))

        val track = mixer.createTrack()
        assertTrue(track.setAudio(audio), "setAudio: ${SDLMixer.error()}")
        assertTrue(track.play(), "play: ${SDLMixer.error()}")
        assertTrue(track.playing, "track should be playing")

        // Generate one second of stereo F32 audio (4 bytes per sample frame).
        val buffer = ByteArray(48000 * 4)
        val mixed = mixer.generate(buffer)
        assertTrue(mixed > 0, "generate returned $mixed: ${SDLMixer.error()}")
        val nonSilent = buffer.any { it != 0.toByte() }
        assertTrue(nonSilent, "generated audio is all silence")

        assertTrue(track.stop(), "stop: ${SDLMixer.error()}")
        assertFalse(track.playing, "track should be stopped")

        track.close()
        audio.close()
        mixer.close()
        SDLMixer.quit()
    }

    @Test
    fun tagsAndCallbacks() {
        assertTrue(SDLMixer.init(), "MIX_Init failed: ${SDLMixer.error()}")
        val mixer = SDLMixer.createMixer(SDLAudioSpec(SDLAudioFormat.F32, 2, 48000))
        val audio = mixer.createSineWaveAudio(880, 0.3f, 500)
        val track = mixer.createTrack()

        assertTrue(track.setAudio(audio))
        assertTrue(track.tag("test"), "tag: ${SDLMixer.error()}")
        assertEquals(listOf("test"), track.tags)
        track.untag("test")
        assertTrue(track.tags.isEmpty(), "tags after untag: ${track.tags}")

        var stopped = false
        track.setStoppedCallback { stopped = true }

        assertTrue(track.play())
        // The sine wave audio is mono, so a stereo output buffer consumes one
        // input frame per output frame; exhaustion is noticed on the next mix
        // cycle. Generate well past the audio's 500ms duration.
        val buffer = ByteArray(4800 * 4)
        repeat(24) { mixer.generate(buffer) }
        assertFalse(track.playing, "track should have stopped after its audio ran out")
        assertTrue(stopped, "track stopped callback did not fire")

        track.close()
        audio.close()
        mixer.close()
        SDLMixer.quit()
    }
}
