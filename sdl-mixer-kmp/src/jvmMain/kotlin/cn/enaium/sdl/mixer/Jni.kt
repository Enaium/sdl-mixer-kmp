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

import cn.enaium.sdl.SDLAudioSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JNI bridge for the JVM target.
 *
 * Every `external fun` maps 1:1 to a `Java_cn_enaium_sdl_mixer_Jni_<name>`
 * function in jni/jni_mixer.cpp. All members are public (no `internal`
 * modifier) so their JVM names are not mangled by the Kotlin compiler.
 *
 * The underlying libsdl_mixer_jni shared library statically links its own
 * SDL3 and SDL_mixer copies; the init block still touches sdl-kmp first so
 * libsdl_jni (and the SDL3 bindings) are usable by the time the mixer library
 * is loaded.
 *
 * Callbacks: the mixing callbacks fire on the audio device thread. The C side
 * dispatches to the `call*` methods below with the id of a callback
 * registered in [CallbackRegistry]; the userdata pointer carries that id.
 */
internal object Jni {

    init {
        // Make sure sdl-kmp's Jni object is initialized (loading libsdl_jni)
        // before libsdl_mixer_jni is dlopen()ed.
        cn.enaium.sdl.SDL.error()
        MixerNativeLoader.load()
    }

    /**
     * Id -> callback mapping for the mixing callbacks. Ids are stable and
     * reused through the registry; a callback is removed when it is replaced,
     * cleared, or its owner is closed.
     */
    object CallbackRegistry {
        private val nextId = AtomicLong(1)
        private val callbacks = ConcurrentHashMap<Long, Any>()

        fun register(callback: Any): Long {
            val id = nextId.getAndIncrement()
            callbacks[id] = callback
            return id
        }

        fun unregister(id: Long) {
            callbacks.remove(id)
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> get(id: Long): T? = callbacks[id] as T?
    }

    // =========================================================================
    // Core
    // =========================================================================

    external fun version(): Int
    external fun init(): Boolean
    external fun quit()
    external fun numAudioDecoders(): Int
    external fun audioDecoder(index: Int): String?
    external fun getError(): String?
    external fun clearError()
    external fun msToFrames(sampleRate: Int, ms: Long): Long
    external fun framesToMS(sampleRate: Int, frames: Long): Long

    // =========================================================================
    // Mixers
    // =========================================================================

    external fun createMixerDevice(deviceId: Int, format: Int, channels: Int, freq: Int): Long
    external fun createMixer(format: Int, channels: Int, freq: Int): Long
    external fun destroyMixer(mixer: Long)
    external fun getMixerFormat(mixer: Long): IntArray?
    external fun lockMixer(mixer: Long)
    external fun unlockMixer(mixer: Long)
    external fun setMixerGain(mixer: Long, gain: Float): Boolean
    external fun getMixerGain(mixer: Long): Float
    external fun setMixerFrequencyRatio(mixer: Long, ratio: Float): Boolean
    external fun getMixerFrequencyRatio(mixer: Long): Float
    external fun playAudio(mixer: Long, audio: Long): Boolean
    external fun playTag(mixer: Long, tag: String, options: Int): Boolean
    external fun stopAllTracks(mixer: Long, fadeOutMs: Long): Boolean
    external fun stopTag(mixer: Long, tag: String, fadeOutMs: Long): Boolean
    external fun pauseAllTracks(mixer: Long): Boolean
    external fun pauseTag(mixer: Long, tag: String): Boolean
    external fun resumeAllTracks(mixer: Long): Boolean
    external fun resumeTag(mixer: Long, tag: String): Boolean
    external fun setTagGain(mixer: Long, tag: String, gain: Float): Boolean
    external fun getTaggedTracks(mixer: Long, tag: String): LongArray?
    external fun setPostMixCallback(mixer: Long, id: Long): Boolean
    external fun generate(mixer: Long, buffer: ByteArray): Int

    // =========================================================================
    // Audio
    // =========================================================================

    external fun loadAudio(mixer: Long, path: String, predecode: Boolean): Long
    external fun loadAudioIO(mixer: Long, io: Long, predecode: Boolean, closeio: Boolean): Long
    external fun loadAudioNoCopy(mixer: Long, data: ByteArray): Long
    external fun loadAudioWithProperties(props: Int): Long
    external fun loadRawAudio(mixer: Long, data: ByteArray, format: Int, channels: Int, freq: Int): Long
    external fun loadRawAudioNoCopy(mixer: Long, data: ByteArray, format: Int, channels: Int, freq: Int): Long
    external fun createSineWaveAudio(mixer: Long, hz: Int, amplitude: Float, ms: Long): Long
    external fun getAudioDuration(audio: Long): Long
    external fun getAudioFormat(audio: Long): IntArray?
    external fun getAudioProperties(audio: Long): Int
    external fun audioMSToFrames(audio: Long, ms: Long): Long
    external fun audioFramesToMS(audio: Long, frames: Long): Long
    external fun destroyAudio(audio: Long)

    // =========================================================================
    // Tracks
    // =========================================================================

    external fun createTrack(mixer: Long): Long
    external fun destroyTrack(track: Long)
    external fun setTrackAudio(track: Long, audio: Long): Boolean
    external fun setTrackAudioStream(track: Long, stream: Long): Boolean
    external fun setTrackIOStream(track: Long, io: Long, closeio: Boolean): Boolean
    external fun setTrackRawIOStream(track: Long, io: Long, format: Int, channels: Int, freq: Int, closeio: Boolean): Boolean
    external fun tagTrack(track: Long, tag: String): Boolean
    external fun untagTrack(track: Long, tag: String?)
    external fun getTrackTags(track: Long): Array<String>?
    external fun setTrackPlaybackPosition(track: Long, frames: Long): Boolean
    external fun getTrackPlaybackPosition(track: Long): Long
    external fun getTrackFadeFrames(track: Long): Long
    external fun getTrackLoops(track: Long): Int
    external fun setTrackLoops(track: Long, loops: Int): Boolean
    external fun getTrackAudio(track: Long): Long
    external fun getTrackAudioStream(track: Long): Long
    external fun getTrackRemaining(track: Long): Long
    external fun trackMSToFrames(track: Long, ms: Long): Long
    external fun trackFramesToMS(track: Long, frames: Long): Long
    external fun playTrack(track: Long, options: Int): Boolean
    external fun stopTrack(track: Long, fadeOutFrames: Long): Boolean
    external fun pauseTrack(track: Long): Boolean
    external fun resumeTrack(track: Long): Boolean
    external fun trackPlaying(track: Long): Boolean
    external fun trackPaused(track: Long): Boolean
    external fun setTrackGain(track: Long, gain: Float): Boolean
    external fun getTrackGain(track: Long): Float
    external fun setTrackFrequencyRatio(track: Long, ratio: Float): Boolean
    external fun getTrackFrequencyRatio(track: Long): Float
    external fun setTrackOutputChannelMap(track: Long, chmap: IntArray?): Boolean
    external fun setTrackStereo(track: Long, hasGains: Boolean, left: Float, right: Float): Boolean
    external fun setTrack3DPosition(track: Long, hasPosition: Boolean, x: Float, y: Float, z: Float): Boolean
    external fun getTrack3DPosition(track: Long): FloatArray?
    external fun setTrackGroup(track: Long, group: Long): Boolean
    external fun setTrackStoppedCallback(track: Long, id: Long): Boolean
    external fun setTrackRawCallback(track: Long, id: Long): Boolean
    external fun setTrackCookedCallback(track: Long, id: Long): Boolean

    // =========================================================================
    // Groups
    // =========================================================================

    external fun createGroup(mixer: Long): Long
    external fun destroyGroup(group: Long)
    external fun getGroupProperties(group: Long): Int
    external fun setGroupPostMixCallback(group: Long, id: Long): Boolean

    // =========================================================================
    // Audio decoders
    // =========================================================================

    external fun createAudioDecoder(path: String, props: Int): Long
    external fun createAudioDecoderIO(io: Long, closeio: Boolean, props: Int): Long
    external fun destroyAudioDecoder(decoder: Long)
    external fun getAudioDecoderFormat(decoder: Long): IntArray?
    external fun decodeAudio(decoder: Long, buffer: ByteArray, format: Int, channels: Int, freq: Int): Int

    // =========================================================================
    // Callback dispatch (called from jni_mixer.cpp on the audio device thread)
    // =========================================================================

    @JvmStatic
    fun callTrackStopped(id: Long, trackPtr: Long) {
        // A stale id fires after a callback was re-registered; the kind bits
        // tell the dispatcher which callback type this id belongs to.
        if (id.callbackKind() != MixerCallbackKind.TRACK_STOPPED) return
        CallbackRegistry.get<SDLTrackStoppedCallback>(id.registryId())?.invoke(JvmSDLTrack(trackPtr, owned = false))
    }

    @JvmStatic
    fun callTrackMix(id: Long, trackPtr: Long, format: Int, channels: Int, freq: Int, pcm: FloatArray, samples: Int) {
        if (id.callbackKind() != MixerCallbackKind.TRACK_RAW && id.callbackKind() != MixerCallbackKind.TRACK_COOKED) return
        CallbackRegistry.get<SDLTrackMixCallback>(id.registryId())?.invoke(
            JvmSDLTrack(trackPtr, owned = false),
            SDLAudioSpec(format, channels, freq),
            pcm,
        )
    }

    @JvmStatic
    fun callGroupMix(id: Long, groupPtr: Long, format: Int, channels: Int, freq: Int, pcm: FloatArray, samples: Int) {
        if (id.callbackKind() != MixerCallbackKind.GROUP_POST) return
        CallbackRegistry.get<SDLGroupMixCallback>(id.registryId())?.invoke(
            JvmSDLMixerGroup(groupPtr, owned = false),
            SDLAudioSpec(format, channels, freq),
            pcm,
        )
    }

    @JvmStatic
    fun callPostMix(id: Long, mixerPtr: Long, format: Int, channels: Int, freq: Int, pcm: FloatArray, samples: Int) {
        if (id.callbackKind() != MixerCallbackKind.POST) return
        CallbackRegistry.get<SDLPostMixCallback>(id.registryId())?.invoke(
            JvmSDLMixerDevice(mixerPtr, owned = false),
            SDLAudioSpec(format, channels, freq),
            pcm,
        )
    }
}
