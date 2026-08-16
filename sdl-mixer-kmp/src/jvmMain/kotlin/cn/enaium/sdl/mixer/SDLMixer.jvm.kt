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
import cn.enaium.sdl.SDLIOStream
import cn.enaium.sdl.SDL

internal object MixerCallbackKind {
    const val NONE = 0L
    const val TRACK_STOPPED = 1L
    const val TRACK_RAW = 2L
    const val TRACK_COOKED = 3L
    const val GROUP_POST = 4L
    const val POST = 5L
}

/**
 * The shared userdata id handed to the C callbacks. The low bits identify
 * which kind of callback fired (so the Jni.call* dispatchers can drop stale
 * callbacks after re-registration), the upper bits carry the registry id.
 */
private fun callbackId(kind: Long, registryId: Long): Long = (registryId shl 4) or kind

internal fun Long.registryId(): Long = this ushr 4

internal fun Long.callbackKind(): Long = this and 0xF

internal class JvmSDLMixerDevice internal constructor(
    ptr: Long,
    internal val owned: Boolean,
) : SDLMixerDevice {

    internal var mixer: Long = ptr

    private var postMixId: Long = 0L
    private var postMixCallback: SDLPostMixCallback? = null

    override val ptr: Long get() = mixer

    internal fun check(): Long =
        mixer.also { if (it == 0L) throw IllegalStateException("SDL_mixer mixer is closed") }

    override val format: SDLAudioSpec?
        get() = Jni.getMixerFormat(check())?.toSpec()

    override var gain: Float
        get() = Jni.getMixerGain(check())
        set(value) {
            Jni.setMixerGain(check(), value)
        }

    override var frequencyRatio: Float
        get() = Jni.getMixerFrequencyRatio(check())
        set(value) {
            Jni.setMixerFrequencyRatio(check(), value)
        }

    override fun lock() {
        Jni.lockMixer(check())
    }

    override fun unlock() {
        Jni.unlockMixer(check())
    }

    override fun loadAudio(path: String, predecode: Boolean): SDLAudio {
        val audio = Jni.loadAudio(check(), path, predecode)
        check(audio != 0L) { "MIX_LoadAudio failed: ${SDLMixer.error()}" }
        return JvmSDLAudio(audio)
    }

    override fun loadAudioIO(io: SDLIOStream, predecode: Boolean, closeIO: Boolean): SDLAudio {
        if (io.ptr == 0L) throw IllegalStateException("SDL IO stream is closed")
        val audio = Jni.loadAudioIO(check(), io.ptr, predecode, closeIO)
        check(audio != 0L) { "MIX_LoadAudio_IO failed: ${SDLMixer.error()}" }
        return JvmSDLAudio(audio)
    }

    override fun loadAudioNoCopy(data: ByteArray): SDLAudio {
        val audio = Jni.loadAudioNoCopy(check(), data)
        check(audio != 0L) { "MIX_LoadAudioNoCopy failed: ${SDLMixer.error()}" }
        return JvmSDLAudio(audio)
    }

    override fun loadAudioWithProperties(props: ULong): SDLAudio {
        val audio = Jni.loadAudioWithProperties(props.toInt())
        check(audio != 0L) { "MIX_LoadAudioWithProperties failed: ${SDLMixer.error()}" }
        return JvmSDLAudio(audio)
    }

    override fun loadRawAudio(data: ByteArray, spec: SDLAudioSpec): SDLAudio {
        val audio = Jni.loadRawAudio(check(), data, spec.format, spec.channels, spec.freq)
        check(audio != 0L) { "MIX_LoadRawAudio failed: ${SDLMixer.error()}" }
        return JvmSDLAudio(audio)
    }

    override fun loadRawAudioNoCopy(data: ByteArray, spec: SDLAudioSpec): SDLAudio {
        val audio = Jni.loadRawAudioNoCopy(check(), data, spec.format, spec.channels, spec.freq)
        check(audio != 0L) { "MIX_LoadRawAudioNoCopy failed: ${SDLMixer.error()}" }
        return JvmSDLAudio(audio)
    }

    override fun createSineWaveAudio(hz: Int, amplitude: Float, ms: Long): SDLAudio {
        val audio = Jni.createSineWaveAudio(check(), hz, amplitude, ms)
        check(audio != 0L) { "MIX_CreateSineWaveAudio failed: ${SDLMixer.error()}" }
        return JvmSDLAudio(audio)
    }

    override fun createTrack(): SDLTrack {
        val track = Jni.createTrack(check())
        check(track != 0L) { "MIX_CreateTrack failed: ${SDLMixer.error()}" }
        return JvmSDLTrack(track, owned = true)
    }

    override fun createGroup(): SDLMixerGroup {
        val group = Jni.createGroup(check())
        check(group != 0L) { "MIX_CreateGroup failed: ${SDLMixer.error()}" }
        return JvmSDLMixerGroup(group, owned = true)
    }

    override fun playAudio(audio: SDLAudio): Boolean {
        val a = (audio as? JvmSDLAudio)?.check()
            ?: throw IllegalArgumentException("audio is not a JVM SDL_mixer audio")
        return Jni.playAudio(check(), a)
    }

    override fun playTag(tag: String, options: ULong): Boolean = Jni.playTag(check(), tag, options.toInt())

    override fun stopAllTracks(fadeOutMs: Long): Boolean = Jni.stopAllTracks(check(), fadeOutMs)

    override fun stopTag(tag: String, fadeOutMs: Long): Boolean = Jni.stopTag(check(), tag, fadeOutMs)

    override fun pauseAllTracks(): Boolean = Jni.pauseAllTracks(check())

    override fun pauseTag(tag: String): Boolean = Jni.pauseTag(check(), tag)

    override fun resumeAllTracks(): Boolean = Jni.resumeAllTracks(check())

    override fun resumeTag(tag: String): Boolean = Jni.resumeTag(check(), tag)

    override fun setTagGain(tag: String, gain: Float): Boolean = Jni.setTagGain(check(), tag, gain)

    override fun taggedTracks(tag: String): List<SDLTrack> =
        Jni.getTaggedTracks(check(), tag)?.map { JvmSDLTrack(it, owned = false) } ?: emptyList()

    override fun setPostMixCallback(callback: SDLPostMixCallback?) {
        val m = check()
        if (postMixId != 0L) {
            Jni.CallbackRegistry.unregister(postMixId.registryId())
            postMixId = 0L
        }
        postMixCallback = callback
        if (callback != null) {
            val id = callbackId(MixerCallbackKind.POST, Jni.CallbackRegistry.register(callback))
            check(Jni.setPostMixCallback(m, id)) { "MIX_SetPostMixCallback failed: ${SDLMixer.error()}" }
            postMixId = id
        } else {
            Jni.setPostMixCallback(m, 0L)
        }
    }

    override fun generate(buffer: ByteArray): Int = Jni.generate(check(), buffer)

    override fun close() {
        val m = mixer
        if (m == 0L) return
        mixer = 0L
        if (postMixId != 0L) {
            Jni.CallbackRegistry.unregister(postMixId.registryId())
            postMixId = 0L
        }
        postMixCallback = null
        if (owned) {
            Jni.destroyMixer(m)
        }
    }
}

internal class JvmSDLAudio internal constructor(ptr: Long) : SDLAudio {

    internal var audio: Long = ptr

    override val ptr: Long get() = audio

    internal fun check(): Long =
        audio.also { if (it == 0L) throw IllegalStateException("SDL_mixer audio is closed") }

    override val duration: Long
        get() = Jni.getAudioDuration(check())

    override val format: SDLAudioSpec?
        get() = Jni.getAudioFormat(check())?.toSpec()

    override val properties: ULong
        get() = Jni.getAudioProperties(check()).toUInt().toULong()

    override fun msToFrames(ms: Long): Long = Jni.audioMSToFrames(check(), ms)

    override fun framesToMS(frames: Long): Long = Jni.audioFramesToMS(check(), frames)

    override fun close() {
        val a = audio
        if (a == 0L) return
        audio = 0L
        Jni.destroyAudio(a)
    }
}

internal class JvmSDLTrack internal constructor(
    ptr: Long,
    internal val owned: Boolean,
) : SDLTrack {

    internal var track: Long = ptr

    private var stoppedId: Long = 0L
    private var rawId: Long = 0L
    private var cookedId: Long = 0L

    override val ptr: Long get() = track

    internal fun check(): Long =
        track.also { if (it == 0L) throw IllegalStateException("SDL_mixer track is closed") }

    override val playing: Boolean get() = Jni.trackPlaying(check())
    override val paused: Boolean get() = Jni.trackPaused(check())

    override var gain: Float
        get() = Jni.getTrackGain(check())
        set(value) {
            Jni.setTrackGain(check(), value)
        }

    override var frequencyRatio: Float
        get() = Jni.getTrackFrequencyRatio(check())
        set(value) {
            Jni.setTrackFrequencyRatio(check(), value)
        }

    override var loops: Int
        get() = Jni.getTrackLoops(check())
        set(value) {
            Jni.setTrackLoops(check(), value)
        }

    override var playbackPosition: Long
        get() = Jni.getTrackPlaybackPosition(check())
        set(value) {
            Jni.setTrackPlaybackPosition(check(), value)
        }

    override val remaining: Long get() = Jni.getTrackRemaining(check())
    override val fadeFrames: Long get() = Jni.getTrackFadeFrames(check())

    override val audio: SDLAudio?
        get() = Jni.getTrackAudio(check()).takeIf { it != 0L }?.let { JvmSDLAudio(it) }

    override val audioStreamPtr: Long get() = Jni.getTrackAudioStream(check())

    override val tags: List<String>
        get() = Jni.getTrackTags(check())?.toList() ?: emptyList()

    override fun setAudio(audio: SDLAudio?): Boolean {
        val a = (audio as? JvmSDLAudio)?.check()
            ?: if (audio == null) 0L else throw IllegalArgumentException("audio is not a JVM SDL_mixer audio")
        return Jni.setTrackAudio(check(), a)
    }

    override fun setAudioStream(streamPtr: Long): Boolean = Jni.setTrackAudioStream(check(), streamPtr)

    override fun setIOStream(io: SDLIOStream, closeIO: Boolean): Boolean {
        if (io.ptr == 0L) throw IllegalStateException("SDL IO stream is closed")
        return Jni.setTrackIOStream(check(), io.ptr, closeIO)
    }

    override fun setRawIOStream(io: SDLIOStream, spec: SDLAudioSpec, closeIO: Boolean): Boolean {
        if (io.ptr == 0L) throw IllegalStateException("SDL IO stream is closed")
        return Jni.setTrackRawIOStream(check(), io.ptr, spec.format, spec.channels, spec.freq, closeIO)
    }

    override fun tag(tag: String): Boolean = Jni.tagTrack(check(), tag)

    override fun untag(tag: String?) {
        Jni.untagTrack(check(), tag)
    }

    override fun play(options: ULong): Boolean = Jni.playTrack(check(), options.toInt())

    override fun stop(fadeOutFrames: Long): Boolean = Jni.stopTrack(check(), fadeOutFrames)

    override fun pause(): Boolean = Jni.pauseTrack(check())

    override fun resume(): Boolean = Jni.resumeTrack(check())

    override fun msToFrames(ms: Long): Long = Jni.trackMSToFrames(check(), ms)

    override fun framesToMS(frames: Long): Long = Jni.trackFramesToMS(check(), frames)

    override fun setOutputChannelMap(chmap: IntArray?): Boolean = Jni.setTrackOutputChannelMap(check(), chmap)

    override fun setStereo(gains: SDLMixerStereoGains?): Boolean {
        val g = gains ?: return Jni.setTrackStereo(check(), false, 0f, 0f)
        return Jni.setTrackStereo(check(), true, g.left, g.right)
    }

    override fun set3DPosition(position: SDLMixerPoint3D?): Boolean {
        val p = position ?: return Jni.setTrack3DPosition(check(), false, 0f, 0f, 0f)
        return Jni.setTrack3DPosition(check(), true, p.x, p.y, p.z)
    }

    override fun get3DPosition(): SDLMixerPoint3D? =
        Jni.getTrack3DPosition(check())?.let { SDLMixerPoint3D(it[0], it[1], it[2]) }

    override fun setGroup(group: SDLMixerGroup?): Boolean {
        val g = (group as? JvmSDLMixerGroup)?.check()
            ?: if (group == null) 0L else throw IllegalArgumentException("group is not a JVM SDL_mixer group")
        return Jni.setTrackGroup(check(), g)
    }

    override fun setStoppedCallback(callback: SDLTrackStoppedCallback?) {
        setCallback(MixerCallbackKind.TRACK_STOPPED, callback) { id ->
            Jni.setTrackStoppedCallback(check(), id)
        }.also { stoppedId = it }
    }

    override fun setRawCallback(callback: SDLTrackMixCallback?) {
        setCallback(MixerCallbackKind.TRACK_RAW, callback) { id ->
            Jni.setTrackRawCallback(check(), id)
        }.also { rawId = it }
    }

    override fun setCookedCallback(callback: SDLTrackMixCallback?) {
        setCallback(MixerCallbackKind.TRACK_COOKED, callback) { id ->
            Jni.setTrackCookedCallback(check(), id)
        }.also { cookedId = it }
    }

    private fun setCallback(kind: Long, callback: Any?, set: (Long) -> Boolean): Long {
        val t = check()
        val oldId = when (kind) {
            MixerCallbackKind.TRACK_STOPPED -> stoppedId
            MixerCallbackKind.TRACK_RAW -> rawId
            else -> cookedId
        }
        if (oldId != 0L) {
            Jni.CallbackRegistry.unregister(oldId.registryId())
        }
        if (callback == null) {
            set(0L)
            return 0L
        }
        val id = callbackId(kind, Jni.CallbackRegistry.register(callback))
        check(set(id)) { "Failed to set mixer callback: ${SDLMixer.error()}" }
        return id
    }

    override fun close() {
        val t = track
        if (t == 0L) return
        track = 0L
        listOfNotNull(stoppedId.takeIf { it != 0L }, rawId.takeIf { it != 0L }, cookedId.takeIf { it != 0L })
            .forEach { Jni.CallbackRegistry.unregister(it.registryId()) }
        stoppedId = 0L
        rawId = 0L
        cookedId = 0L
        if (owned) {
            Jni.destroyTrack(t)
        }
    }
}

internal class JvmSDLMixerGroup internal constructor(
    ptr: Long,
    internal val owned: Boolean,
) : SDLMixerGroup {

    internal var group: Long = ptr

    private var postMixId: Long = 0L

    override val ptr: Long get() = group

    internal fun check(): Long =
        group.also { if (it == 0L) throw IllegalStateException("SDL_mixer group is closed") }

    override fun setPostMixCallback(callback: SDLGroupMixCallback?) {
        val g = check()
        if (postMixId != 0L) {
            Jni.CallbackRegistry.unregister(postMixId.registryId())
            postMixId = 0L
        }
        if (callback == null) {
            Jni.setGroupPostMixCallback(g, 0L)
            return
        }
        val id = callbackId(MixerCallbackKind.GROUP_POST, Jni.CallbackRegistry.register(callback))
        check(Jni.setGroupPostMixCallback(g, id)) { "MIX_SetGroupPostMixCallback failed: ${SDLMixer.error()}" }
        postMixId = id
    }

    override fun close() {
        val g = group
        if (g == 0L) return
        group = 0L
        if (postMixId != 0L) {
            Jni.CallbackRegistry.unregister(postMixId.registryId())
            postMixId = 0L
        }
        if (owned) {
            Jni.destroyGroup(g)
        }
    }
}

internal class JvmSDLAudioDecoder internal constructor(ptr: Long) : SDLAudioDecoder {

    internal var decoder: Long = ptr

    override val ptr: Long get() = decoder

    internal fun check(): Long =
        decoder.also { if (it == 0L) throw IllegalStateException("SDL_mixer audio decoder is closed") }

    override val format: SDLAudioSpec?
        get() = Jni.getAudioDecoderFormat(check())?.toSpec()

    override fun decode(buffer: ByteArray, spec: SDLAudioSpec): Int =
        Jni.decodeAudio(check(), buffer, spec.format, spec.channels, spec.freq)

    override fun close() {
        val d = decoder
        if (d == 0L) return
        decoder = 0L
        Jni.destroyAudioDecoder(d)
    }
}

private fun IntArray.toSpec(): SDLAudioSpec = SDLAudioSpec(this[0], this[1], this[2])

actual object SDLMixer {

    actual fun version(): Int = Jni.version()

    actual fun init(): Boolean = Jni.init()

    actual fun quit() {
        Jni.quit()
    }

    actual fun numAudioDecoders(): Int = Jni.numAudioDecoders()

    actual fun audioDecoder(index: Int): String? = Jni.audioDecoder(index)

    actual fun error(): String? = Jni.getError()?.takeIf { it.isNotEmpty() }

    actual fun clearError() {
        Jni.clearError()
    }

    actual fun createMixerDevice(deviceId: Int, spec: SDLAudioSpec?): SDLMixerDevice {
        val s = spec
        val mixer = if (s == null) {
            Jni.createMixerDevice(deviceId, -1, 0, 0)
        } else {
            Jni.createMixerDevice(deviceId, s.format, s.channels, s.freq)
        }
        check(mixer != 0L) { "MIX_CreateMixerDevice failed: ${SDLMixer.error()}" }
        return JvmSDLMixerDevice(mixer, owned = true)
    }

    actual fun createMixer(spec: SDLAudioSpec): SDLMixerDevice {
        val mixer = Jni.createMixer(spec.format, spec.channels, spec.freq)
        check(mixer != 0L) { "MIX_CreateMixer failed: ${SDLMixer.error()}" }
        return JvmSDLMixerDevice(mixer, owned = true)
    }

    actual fun createAudioDecoder(path: String, props: ULong): SDLAudioDecoder {
        val decoder = Jni.createAudioDecoder(path, props.toInt())
        check(decoder != 0L) { "MIX_CreateAudioDecoder failed: ${SDLMixer.error()}" }
        return JvmSDLAudioDecoder(decoder)
    }

    actual fun createAudioDecoderIO(io: SDLIOStream, closeIO: Boolean, props: ULong): SDLAudioDecoder {
        if (io.ptr == 0L) throw IllegalStateException("SDL IO stream is closed")
        val decoder = Jni.createAudioDecoderIO(io.ptr, closeIO, props.toInt())
        check(decoder != 0L) { "MIX_CreateAudioDecoder_IO failed: ${SDLMixer.error()}" }
        return JvmSDLAudioDecoder(decoder)
    }

    actual fun msToFrames(sampleRate: Int, ms: Long): Long = Jni.msToFrames(sampleRate, ms)

    actual fun framesToMS(sampleRate: Int, frames: Long): Long = Jni.framesToMS(sampleRate, frames)
}
