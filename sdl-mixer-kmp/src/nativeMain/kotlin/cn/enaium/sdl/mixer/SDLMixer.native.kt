@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.sdl.mixer

import cn.enaium.sdl.SDLAudioSpec
import cn.enaium.sdl.SDLIOStream
import kotlinx.cinterop.*
import sdl3.SDL_free
import cnames.structs.MIX_Audio
import cnames.structs.MIX_AudioDecoder
import cnames.structs.MIX_Group
import cnames.structs.MIX_Mixer
import cnames.structs.MIX_Track
import sdl3.SDL_AudioSpec
import cnames.structs.SDL_AudioStream
import cnames.structs.SDL_IOStream
import sdl_mixer.MIX_AudioFramesToMS
import sdl_mixer.MIX_AudioMSToFrames
import sdl_mixer.MIX_CreateAudioDecoder
import sdl_mixer.MIX_CreateAudioDecoder_IO
import sdl_mixer.MIX_CreateGroup
import sdl_mixer.MIX_CreateMixer
import sdl_mixer.MIX_CreateMixerDevice
import sdl_mixer.MIX_CreateSineWaveAudio
import sdl_mixer.MIX_CreateTrack
import sdl_mixer.MIX_DecodeAudio
import sdl_mixer.MIX_DestroyAudio
import sdl_mixer.MIX_DestroyAudioDecoder
import sdl_mixer.MIX_DestroyGroup
import sdl_mixer.MIX_DestroyMixer
import sdl_mixer.MIX_DestroyTrack
import sdl_mixer.MIX_FramesToMS
import sdl_mixer.MIX_Generate
import sdl_mixer.MIX_GetAudioDecoder
import sdl_mixer.MIX_GetAudioDecoderFormat
import sdl_mixer.MIX_GetAudioDuration
import sdl_mixer.MIX_GetAudioFormat
import sdl_mixer.MIX_GetAudioProperties
import sdl_mixer.MIX_GetMixerFormat
import sdl_mixer.MIX_GetMixerFrequencyRatio
import sdl_mixer.MIX_GetMixerGain
import sdl_mixer.MIX_GetNumAudioDecoders
import sdl_mixer.MIX_GetTaggedTracks
import sdl_mixer.MIX_GetTrack3DPosition
import sdl_mixer.MIX_GetTrackAudio
import sdl_mixer.MIX_GetTrackAudioStream
import sdl_mixer.MIX_GetTrackFadeFrames
import sdl_mixer.MIX_GetTrackFrequencyRatio
import sdl_mixer.MIX_GetTrackGain
import sdl_mixer.MIX_GetTrackLoops
import sdl_mixer.MIX_GetTrackPlaybackPosition
import sdl_mixer.MIX_GetTrackRemaining
import sdl_mixer.MIX_GetTrackTags
import sdl_mixer.MIX_Init
import sdl_mixer.MIX_LoadAudio
import sdl_mixer.MIX_LoadAudioNoCopy
import sdl_mixer.MIX_LoadAudioWithProperties
import sdl_mixer.MIX_LoadAudio_IO
import sdl_mixer.MIX_LoadRawAudio
import sdl_mixer.MIX_LoadRawAudioNoCopy
import sdl_mixer.MIX_LockMixer
import sdl_mixer.MIX_MSToFrames
import sdl_mixer.MIX_PauseAllTracks
import sdl_mixer.MIX_PauseTag
import sdl_mixer.MIX_PauseTrack
import sdl_mixer.MIX_PlayAudio
import sdl_mixer.MIX_PlayTag
import sdl_mixer.MIX_PlayTrack
import sdl_mixer.MIX_Quit
import sdl_mixer.MIX_ResumeAllTracks
import sdl_mixer.MIX_ResumeTag
import sdl_mixer.MIX_ResumeTrack
import sdl_mixer.MIX_SetGroupPostMixCallback
import sdl_mixer.MIX_SetMixerFrequencyRatio
import sdl_mixer.MIX_SetMixerGain
import sdl_mixer.MIX_SetPostMixCallback
import sdl_mixer.MIX_SetTagGain
import sdl_mixer.MIX_SetTrack3DPosition
import sdl_mixer.MIX_SetTrackAudio
import sdl_mixer.MIX_SetTrackAudioStream
import sdl_mixer.MIX_SetTrackCookedCallback
import sdl_mixer.MIX_SetTrackFrequencyRatio
import sdl_mixer.MIX_SetTrackGain
import sdl_mixer.MIX_SetTrackGroup
import sdl_mixer.MIX_SetTrackIOStream
import sdl_mixer.MIX_SetTrackLoops
import sdl_mixer.MIX_SetTrackOutputChannelMap
import sdl_mixer.MIX_SetTrackPlaybackPosition
import sdl_mixer.MIX_SetTrackRawCallback
import sdl_mixer.MIX_SetTrackRawIOStream
import sdl_mixer.MIX_SetTrackStereo
import sdl_mixer.MIX_SetTrackStoppedCallback
import sdl_mixer.MIX_StopAllTracks
import sdl_mixer.MIX_StopTag
import sdl_mixer.MIX_StopTrack
import sdl_mixer.MIX_TagTrack
import sdl_mixer.MIX_TrackFramesToMS
import sdl_mixer.MIX_TrackMSToFrames
import sdl_mixer.MIX_TrackPaused
import sdl_mixer.MIX_TrackPlaying
import sdl_mixer.MIX_UntagTrack
import sdl_mixer.MIX_UnlockMixer
import sdl_mixer.MIX_Version

// =========================================================================
// Callback trampolines
// =========================================================================

internal class TrackStoppedCallbackBox(val callback: SDLTrackStoppedCallback)
internal class TrackMixCallbackBox(val callback: SDLTrackMixCallback)
internal class GroupMixCallbackBox(val callback: SDLGroupMixCallback)
internal class PostMixCallbackBox(val callback: SDLPostMixCallback)

private fun nativeTrackStoppedCallback(userdata: COpaquePointer?, track: CPointer<MIX_Track>?) {
    val box = userdata?.asStableRef<TrackStoppedCallbackBox>()?.get()
    if (box != null) {
        box.callback(NativeSDLTrack(track!!, owned = false))
    }
}

private fun nativeTrackMixCallback(
    userdata: COpaquePointer?,
    track: CPointer<MIX_Track>?,
    spec: CPointer<SDL_AudioSpec>?,
    pcm: CPointer<FloatVar>?,
    samples: Int,
) {
    val box = userdata?.asStableRef<TrackMixCallbackBox>()?.get()
    if (box != null && pcm != null && spec != null) {
        val arr = FloatArray(samples)
        for (i in 0 until samples) {
            arr[i] = pcm[i]
        }
        box.callback(NativeSDLTrack(track!!, owned = false), spec.pointed.toSpec(), arr)
    }
}

private fun nativeGroupMixCallback(
    userdata: COpaquePointer?,
    group: CPointer<MIX_Group>?,
    spec: CPointer<SDL_AudioSpec>?,
    pcm: CPointer<FloatVar>?,
    samples: Int,
) {
    val box = userdata?.asStableRef<GroupMixCallbackBox>()?.get()
    if (box != null && pcm != null && spec != null) {
        val arr = FloatArray(samples)
        for (i in 0 until samples) {
            arr[i] = pcm[i]
        }
        box.callback(NativeSDLMixerGroup(group!!, owned = false), spec.pointed.toSpec(), arr)
    }
}

private fun nativePostMixCallback(
    userdata: COpaquePointer?,
    mixer: CPointer<MIX_Mixer>?,
    spec: CPointer<SDL_AudioSpec>?,
    pcm: CPointer<FloatVar>?,
    samples: Int,
) {
    val box = userdata?.asStableRef<PostMixCallbackBox>()?.get()
    if (box != null && pcm != null && spec != null) {
        val arr = FloatArray(samples)
        for (i in 0 until samples) {
            arr[i] = pcm[i]
        }
        box.callback(NativeSDLMixerDevice(mixer!!, owned = false), spec.pointed.toSpec(), arr)
    }
}

private fun SDL_AudioSpec.toSpec(): SDLAudioSpec = SDLAudioSpec(format.toInt(), channels, freq)

// =========================================================================
// Wrappers
// =========================================================================

internal class NativeSDLMixerDevice internal constructor(
    ptr: CPointer<MIX_Mixer>?,
    internal val owned: Boolean,
) : SDLMixerDevice {

    internal var mixer: CPointer<MIX_Mixer>? = ptr

    private var postMixRef: StableRef<PostMixCallbackBox>? = null

    override val ptr: Long get() = mixer?.rawValue?.toLong() ?: 0L

    internal fun check(): CPointer<MIX_Mixer> =
        mixer ?: throw IllegalStateException("SDL_mixer mixer is closed")

    override val format: SDLAudioSpec?
        get() = memScoped {
            val specPtr = cValue<SDL_AudioSpec>().ptr
            if (MIX_GetMixerFormat(check(), specPtr)) {
                SDLAudioSpec(specPtr.pointed.format.toInt(), specPtr.pointed.channels, specPtr.pointed.freq)
            } else {
                null
            }
        }

    override var gain: Float
        get() = MIX_GetMixerGain(check())
        set(value) {
            MIX_SetMixerGain(check(), value)
        }

    override var frequencyRatio: Float
        get() = MIX_GetMixerFrequencyRatio(check())
        set(value) {
            MIX_SetMixerFrequencyRatio(check(), value)
        }

    override fun lock() {
        MIX_LockMixer(check())
    }

    override fun unlock() {
        MIX_UnlockMixer(check())
    }

    override fun loadAudio(path: String, predecode: Boolean): SDLAudio {
        val audio = MIX_LoadAudio(check(), path, predecode)
            ?: throw IllegalStateException("MIX_LoadAudio failed: ${SDLMixer.error()}")
        return NativeSDLAudio(audio)
    }

    override fun loadAudioIO(io: SDLIOStream, predecode: Boolean, closeIO: Boolean): SDLAudio {
        if (io.ptr == 0L) throw IllegalStateException("SDL IO stream is closed")
        val audio = MIX_LoadAudio_IO(check(), io.ptr.toCPointer<SDL_IOStream>(), predecode, closeIO)
            ?: throw IllegalStateException("MIX_LoadAudio_IO failed: ${SDLMixer.error()}")
        return NativeSDLAudio(audio)
    }

    override fun loadAudioNoCopy(data: ByteArray): SDLAudio {
        val pinned = data.pin()
        val audio = MIX_LoadAudioNoCopy(
            check(),
            pinned.addressOf(0),
            data.size.convert(),
            false,
        ) ?: run {
            pinned.unpin()
            throw IllegalStateException("MIX_LoadAudioNoCopy failed: ${SDLMixer.error()}")
        }
        return NativeSDLAudio(audio, pinned)
    }

    override fun loadAudioWithProperties(props: ULong): SDLAudio {
        val audio = MIX_LoadAudioWithProperties(props.toUInt())
            ?: throw IllegalStateException("MIX_LoadAudioWithProperties failed: ${SDLMixer.error()}")
        return NativeSDLAudio(audio)
    }

    override fun loadRawAudio(data: ByteArray, spec: SDLAudioSpec): SDLAudio = memScoped {
        val specPtr = cValue<SDL_AudioSpec> {
            format = spec.format.toUInt()
            channels = spec.channels
            freq = spec.freq
        }
        val audio = data.usePinned { pinned ->
            MIX_LoadRawAudio(check(), pinned.addressOf(0), data.size.convert(), specPtr)
        } ?: throw IllegalStateException("MIX_LoadRawAudio failed: ${SDLMixer.error()}")
        NativeSDLAudio(audio)
    }

    override fun loadRawAudioNoCopy(data: ByteArray, spec: SDLAudioSpec): SDLAudio = memScoped {
        val specPtr = cValue<SDL_AudioSpec> {
            format = spec.format.toUInt()
            channels = spec.channels
            freq = spec.freq
        }
        val pinned = data.pin()
        val audio = MIX_LoadRawAudioNoCopy(
            check(),
            pinned.addressOf(0),
            data.size.convert(),
            specPtr,
            false,
        ) ?: run {
            pinned.unpin()
            throw IllegalStateException("MIX_LoadRawAudioNoCopy failed: ${SDLMixer.error()}")
        }
        NativeSDLAudio(audio, pinned)
    }

    override fun createSineWaveAudio(hz: Int, amplitude: Float, ms: Long): SDLAudio {
        val audio = MIX_CreateSineWaveAudio(check(), hz, amplitude, ms)
            ?: throw IllegalStateException("MIX_CreateSineWaveAudio failed: ${SDLMixer.error()}")
        return NativeSDLAudio(audio)
    }

    override fun createTrack(): SDLTrack {
        val track = MIX_CreateTrack(check())
            ?: throw IllegalStateException("MIX_CreateTrack failed: ${SDLMixer.error()}")
        return NativeSDLTrack(track, owned = true)
    }

    override fun createGroup(): SDLMixerGroup {
        val group = MIX_CreateGroup(check())
            ?: throw IllegalStateException("MIX_CreateGroup failed: ${SDLMixer.error()}")
        return NativeSDLMixerGroup(group, owned = true)
    }

    override fun playAudio(audio: SDLAudio): Boolean {
        val a = (audio as? NativeSDLAudio)?.check()
            ?: throw IllegalArgumentException("audio is not a native SDL_mixer audio")
        return MIX_PlayAudio(check(), a)
    }

    override fun playTag(tag: String, options: ULong): Boolean = MIX_PlayTag(check(), tag, options.toUInt())

    override fun stopAllTracks(fadeOutMs: Long): Boolean = MIX_StopAllTracks(check(), fadeOutMs)

    override fun stopTag(tag: String, fadeOutMs: Long): Boolean = MIX_StopTag(check(), tag, fadeOutMs)

    override fun pauseAllTracks(): Boolean = MIX_PauseAllTracks(check())

    override fun pauseTag(tag: String): Boolean = MIX_PauseTag(check(), tag)

    override fun resumeAllTracks(): Boolean = MIX_ResumeAllTracks(check())

    override fun resumeTag(tag: String): Boolean = MIX_ResumeTag(check(), tag)

    override fun setTagGain(tag: String, gain: Float): Boolean = MIX_SetTagGain(check(), tag, gain)

    override fun taggedTracks(tag: String): List<SDLTrack> = memScoped {
        val count = alloc<IntVar>()
        val tracks = MIX_GetTaggedTracks(check(), tag, count.ptr)
        if (tracks == null) return emptyList()
        val out = mutableListOf<SDLTrack>()
        for (i in 0 until count.value) {
            out += NativeSDLTrack(tracks[i], owned = false)
        }
        sdl3.SDL_free(tracks)
        out
    }

    override fun setPostMixCallback(callback: SDLPostMixCallback?) {
        val m = check()
        postMixRef?.dispose()
        postMixRef = null
        if (callback == null) {
            MIX_SetPostMixCallback(m, null, null)
            return
        }
        val ref = StableRef.create(PostMixCallbackBox(callback))
        postMixRef = ref
        check(MIX_SetPostMixCallback(m, staticCFunction(::nativePostMixCallback), ref.asCPointer())) {
            "MIX_SetPostMixCallback failed: ${SDLMixer.error()}"
        }
    }

    override fun generate(buffer: ByteArray): Int = buffer.usePinned { pinned ->
        MIX_Generate(check(), pinned.addressOf(0), buffer.size)
    }

    override fun close() {
        val m = mixer
        if (m == null) return
        mixer = null
        postMixRef?.dispose()
        postMixRef = null
        if (owned) {
            MIX_DestroyMixer(m)
        }
    }
}

internal class NativeSDLAudio internal constructor(
    ptr: CPointer<MIX_Audio>?,
    private val pinned: Pinned<ByteArray>? = null,
) : SDLAudio {

    internal var audio: CPointer<MIX_Audio>? = ptr

    override val ptr: Long get() = audio?.rawValue?.toLong() ?: 0L

    internal fun check(): CPointer<MIX_Audio> =
        audio ?: throw IllegalStateException("SDL_mixer audio is closed")

    override val duration: Long
        get() = MIX_GetAudioDuration(check())

    override val format: SDLAudioSpec?
        get() = memScoped {
            val specPtr = cValue<SDL_AudioSpec>().ptr
            if (MIX_GetAudioFormat(check(), specPtr)) {
                specPtr.pointed.toSpec()
            } else {
                null
            }
        }

    override val properties: ULong
        get() = MIX_GetAudioProperties(check()).toULong()

    override fun msToFrames(ms: Long): Long = MIX_AudioMSToFrames(check(), ms)

    override fun framesToMS(frames: Long): Long = MIX_AudioFramesToMS(check(), frames)

    override fun close() {
        val a = audio
        if (a == null) return
        audio = null
        pinned?.unpin()
        MIX_DestroyAudio(a)
    }
}

internal class NativeSDLTrack internal constructor(
    ptr: CPointer<MIX_Track>?,
    internal val owned: Boolean,
) : SDLTrack {

    internal var track: CPointer<MIX_Track>? = ptr

    private var stoppedRef: StableRef<TrackStoppedCallbackBox>? = null
    private var rawRef: StableRef<TrackMixCallbackBox>? = null
    private var cookedRef: StableRef<TrackMixCallbackBox>? = null

    override val ptr: Long get() = track?.rawValue?.toLong() ?: 0L

    internal fun check(): CPointer<MIX_Track> =
        track ?: throw IllegalStateException("SDL_mixer track is closed")

    override val playing: Boolean get() = MIX_TrackPlaying(check())
    override val paused: Boolean get() = MIX_TrackPaused(check())

    override var gain: Float
        get() = MIX_GetTrackGain(check())
        set(value) {
            MIX_SetTrackGain(check(), value)
        }

    override var frequencyRatio: Float
        get() = MIX_GetTrackFrequencyRatio(check())
        set(value) {
            MIX_SetTrackFrequencyRatio(check(), value)
        }

    override var loops: Int
        get() = MIX_GetTrackLoops(check())
        set(value) {
            MIX_SetTrackLoops(check(), value)
        }

    override var playbackPosition: Long
        get() = MIX_GetTrackPlaybackPosition(check())
        set(value) {
            MIX_SetTrackPlaybackPosition(check(), value)
        }

    override val remaining: Long get() = MIX_GetTrackRemaining(check())
    override val fadeFrames: Long get() = MIX_GetTrackFadeFrames(check())

    override val audio: SDLAudio?
        get() = MIX_GetTrackAudio(check())?.let { NativeSDLAudio(it) }

    override val audioStreamPtr: Long
        get() = MIX_GetTrackAudioStream(check())?.rawValue?.toLong() ?: 0L

    override val tags: List<String>
        get() = memScoped {
            val count = alloc<IntVar>()
            val tags = MIX_GetTrackTags(check(), count.ptr)
            if (tags == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until count.value) {
                out += tags[i]?.toKString() ?: ""
            }
            sdl3.SDL_free(tags)
            out
        }

    override fun setAudio(audio: SDLAudio?): Boolean {
        val a = (audio as? NativeSDLAudio)?.check()
            ?: if (audio == null) null else throw IllegalArgumentException("audio is not a native SDL_mixer audio")
        return MIX_SetTrackAudio(check(), a)
    }

    override fun setAudioStream(streamPtr: Long): Boolean {
        if (streamPtr == 0L) throw IllegalStateException("SDL audio stream is closed")
        return MIX_SetTrackAudioStream(check(), streamPtr.toCPointer<SDL_AudioStream>())
    }

    override fun setIOStream(io: SDLIOStream, closeIO: Boolean): Boolean {
        if (io.ptr == 0L) throw IllegalStateException("SDL IO stream is closed")
        return MIX_SetTrackIOStream(check(), io.ptr.toCPointer<SDL_IOStream>(), closeIO)
    }

    override fun setRawIOStream(io: SDLIOStream, spec: SDLAudioSpec, closeIO: Boolean): Boolean = memScoped {
        if (io.ptr == 0L) throw IllegalStateException("SDL IO stream is closed")
        val specPtr = cValue<SDL_AudioSpec> {
            format = spec.format.toUInt()
            channels = spec.channels
            freq = spec.freq
        }
        MIX_SetTrackRawIOStream(check(), io.ptr.toCPointer<SDL_IOStream>(), specPtr, closeIO)
    }

    override fun tag(tag: String): Boolean = MIX_TagTrack(check(), tag)

    override fun untag(tag: String?) {
        MIX_UntagTrack(check(), tag)
    }

    override fun play(options: ULong): Boolean = MIX_PlayTrack(check(), options.toUInt())

    override fun stop(fadeOutFrames: Long): Boolean = MIX_StopTrack(check(), fadeOutFrames)

    override fun pause(): Boolean = MIX_PauseTrack(check())

    override fun resume(): Boolean = MIX_ResumeTrack(check())

    override fun msToFrames(ms: Long): Long = MIX_TrackMSToFrames(check(), ms)

    override fun framesToMS(frames: Long): Long = MIX_TrackFramesToMS(check(), frames)

    override fun setOutputChannelMap(chmap: IntArray?): Boolean = memScoped {
        val count = chmap?.size ?: 0
        val arr = if (chmap != null) allocArray<IntVar>(count).also { map ->
            for (i in 0 until count) {
                map[i] = chmap[i]
            }
        } else null
        MIX_SetTrackOutputChannelMap(check(), arr, count)
    }

    override fun setStereo(gains: SDLMixerStereoGains?): Boolean = memScoped {
        val gainsPtr = gains?.let {
            cValue<sdl_mixer.MIX_StereoGains> {
                left = it.left
                right = it.right
            }.ptr
        }
        MIX_SetTrackStereo(check(), gainsPtr)
    }

    override fun set3DPosition(position: SDLMixerPoint3D?): Boolean = memScoped {
        val posPtr = position?.let {
            cValue<sdl_mixer.MIX_Point3D> {
                x = it.x
                y = it.y
                z = it.z
            }.ptr
        }
        MIX_SetTrack3DPosition(check(), posPtr)
    }

    override fun get3DPosition(): SDLMixerPoint3D? = memScoped {
        val posPtr = cValue<sdl_mixer.MIX_Point3D>().ptr
        if (MIX_GetTrack3DPosition(check(), posPtr)) {
            SDLMixerPoint3D(posPtr.pointed.x, posPtr.pointed.y, posPtr.pointed.z)
        } else {
            null
        }
    }

    override fun setGroup(group: SDLMixerGroup?): Boolean {
        val g = (group as? NativeSDLMixerGroup)?.check()
            ?: if (group == null) null else throw IllegalArgumentException("group is not a native SDL_mixer group")
        return MIX_SetTrackGroup(check(), g)
    }

    override fun setStoppedCallback(callback: SDLTrackStoppedCallback?) {
        val t = check()
        stoppedRef?.dispose()
        stoppedRef = null
        if (callback == null) {
            MIX_SetTrackStoppedCallback(t, null, null)
            return
        }
        val ref = StableRef.create(TrackStoppedCallbackBox(callback))
        stoppedRef = ref
        check(MIX_SetTrackStoppedCallback(t, staticCFunction(::nativeTrackStoppedCallback), ref.asCPointer())) {
            "MIX_SetTrackStoppedCallback failed: ${SDLMixer.error()}"
        }
    }

    override fun setRawCallback(callback: SDLTrackMixCallback?) {
        val t = check()
        rawRef?.dispose()
        rawRef = null
        if (callback == null) {
            MIX_SetTrackRawCallback(t, null, null)
            return
        }
        val ref = StableRef.create(TrackMixCallbackBox(callback))
        rawRef = ref
        check(MIX_SetTrackRawCallback(t, staticCFunction(::nativeTrackMixCallback), ref.asCPointer())) {
            "MIX_SetTrackRawCallback failed: ${SDLMixer.error()}"
        }
    }

    override fun setCookedCallback(callback: SDLTrackMixCallback?) {
        val t = check()
        cookedRef?.dispose()
        cookedRef = null
        if (callback == null) {
            MIX_SetTrackCookedCallback(t, null, null)
            return
        }
        val ref = StableRef.create(TrackMixCallbackBox(callback))
        cookedRef = ref
        check(MIX_SetTrackCookedCallback(t, staticCFunction(::nativeTrackMixCallback), ref.asCPointer())) {
            "MIX_SetTrackCookedCallback failed: ${SDLMixer.error()}"
        }
    }

    override fun close() {
        val t = track
        if (t == null) return
        track = null
        stoppedRef?.dispose()
        stoppedRef = null
        rawRef?.dispose()
        rawRef = null
        cookedRef?.dispose()
        cookedRef = null
        if (owned) {
            MIX_DestroyTrack(t)
        }
    }
}

internal class NativeSDLMixerGroup internal constructor(
    ptr: CPointer<MIX_Group>?,
    internal val owned: Boolean,
) : SDLMixerGroup {

    internal var group: CPointer<MIX_Group>? = ptr

    private var postMixRef: StableRef<GroupMixCallbackBox>? = null

    override val ptr: Long get() = group?.rawValue?.toLong() ?: 0L

    internal fun check(): CPointer<MIX_Group> =
        group ?: throw IllegalStateException("SDL_mixer group is closed")

    override fun setPostMixCallback(callback: SDLGroupMixCallback?) {
        val g = check()
        postMixRef?.dispose()
        postMixRef = null
        if (callback == null) {
            MIX_SetGroupPostMixCallback(g, null, null)
            return
        }
        val ref = StableRef.create(GroupMixCallbackBox(callback))
        postMixRef = ref
        check(MIX_SetGroupPostMixCallback(g, staticCFunction(::nativeGroupMixCallback), ref.asCPointer())) {
            "MIX_SetGroupPostMixCallback failed: ${SDLMixer.error()}"
        }
    }

    override fun close() {
        val g = group
        if (g == null) return
        group = null
        postMixRef?.dispose()
        postMixRef = null
        if (owned) {
            MIX_DestroyGroup(g)
        }
    }
}

internal class NativeSDLAudioDecoder internal constructor(ptr: CPointer<MIX_AudioDecoder>?) : SDLAudioDecoder {

    internal var decoder: CPointer<MIX_AudioDecoder>? = ptr

    override val ptr: Long get() = decoder?.rawValue?.toLong() ?: 0L

    internal fun check(): CPointer<MIX_AudioDecoder> =
        decoder ?: throw IllegalStateException("SDL_mixer audio decoder is closed")

    override val format: SDLAudioSpec?
        get() = memScoped {
            val specPtr = cValue<SDL_AudioSpec>().ptr
            if (MIX_GetAudioDecoderFormat(check(), specPtr)) {
                specPtr.pointed.toSpec()
            } else {
                null
            }
        }

    override fun decode(buffer: ByteArray, spec: SDLAudioSpec): Int = memScoped {
        val specPtr = cValue<SDL_AudioSpec> {
            format = spec.format.toUInt()
            channels = spec.channels
            freq = spec.freq
        }
        buffer.usePinned { pinned ->
            MIX_DecodeAudio(check(), pinned.addressOf(0), buffer.size, specPtr)
        }
    }

    override fun close() {
        val d = decoder
        if (d == null) return
        decoder = null
        MIX_DestroyAudioDecoder(d)
    }
}

// =========================================================================
// The SDL_mixer API
// =========================================================================

actual object SDLMixer {

    actual fun version(): Int = MIX_Version()

    actual fun init(): Boolean = MIX_Init()

    actual fun quit() {
        MIX_Quit()
    }

    actual fun numAudioDecoders(): Int = MIX_GetNumAudioDecoders()

    actual fun audioDecoder(index: Int): String? = MIX_GetAudioDecoder(index)?.toKString()

    actual fun error(): String? = sdl3.SDL_GetError()?.toKString()?.takeIf { it.isNotEmpty() }

    actual fun clearError() {
        sdl3.SDL_ClearError()
    }

    actual fun createMixerDevice(deviceId: Int, spec: SDLAudioSpec?): SDLMixerDevice = memScoped {
        val specPtr = spec?.let {
            cValue<SDL_AudioSpec> {
                format = it.format.toUInt()
                channels = it.channels
                freq = it.freq
            }
        }
        val mixer = MIX_CreateMixerDevice(deviceId.toUInt(), specPtr)
            ?: throw IllegalStateException("MIX_CreateMixerDevice failed: ${SDLMixer.error()}")
        NativeSDLMixerDevice(mixer, owned = true)
    }

    actual fun createMixer(spec: SDLAudioSpec): SDLMixerDevice = memScoped {
        val specPtr = cValue<SDL_AudioSpec> {
            format = spec.format.toUInt()
            channels = spec.channels
            freq = spec.freq
        }
        val mixer = MIX_CreateMixer(specPtr)
            ?: throw IllegalStateException("MIX_CreateMixer failed: ${SDLMixer.error()}")
        NativeSDLMixerDevice(mixer, owned = true)
    }

    actual fun createAudioDecoder(path: String, props: ULong): SDLAudioDecoder {
        val decoder = MIX_CreateAudioDecoder(path, props.toUInt())
            ?: throw IllegalStateException("MIX_CreateAudioDecoder failed: ${SDLMixer.error()}")
        return NativeSDLAudioDecoder(decoder)
    }

    actual fun createAudioDecoderIO(io: SDLIOStream, closeIO: Boolean, props: ULong): SDLAudioDecoder {
        if (io.ptr == 0L) throw IllegalStateException("SDL IO stream is closed")
        val decoder = MIX_CreateAudioDecoder_IO(io.ptr.toCPointer<SDL_IOStream>(), closeIO, props.toUInt())
            ?: throw IllegalStateException("MIX_CreateAudioDecoder_IO failed: ${SDLMixer.error()}")
        return NativeSDLAudioDecoder(decoder)
    }

    actual fun msToFrames(sampleRate: Int, ms: Long): Long = MIX_MSToFrames(sampleRate, ms)

    actual fun framesToMS(sampleRate: Int, frames: Long): Long = MIX_FramesToMS(sampleRate, frames)
}
