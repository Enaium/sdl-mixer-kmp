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

// =========================================================================
// Constants (values match SDL3_mixer's SDL_mixer.h)
// =========================================================================

/** Property names for [SDLMixerDevice.loadAudioWithProperties]. */
object SDLMixerProp {
    const val AUDIO_LOAD_IOSTREAM_POINTER = "SDL_mixer.audio.load.iostream"
    const val AUDIO_LOAD_CLOSEIO_BOOLEAN = "SDL_mixer.audio.load.closeio"
    const val AUDIO_LOAD_PREDECODE_BOOLEAN = "SDL_mixer.audio.load.predecode"
    const val AUDIO_LOAD_PREFERRED_MIXER_POINTER = "SDL_mixer.audio.load.preferred_mixer"
    const val AUDIO_LOAD_SKIP_METADATA_TAGS_BOOLEAN = "SDL_mixer.audio.load.skip_metadata_tags"
    const val AUDIO_LOAD_IGNORE_LOOPS_BOOLEAN = "SDL_mixer.audio.load.ignore_loops"
    const val AUDIO_DECODER_STRING = "SDL_mixer.audio.decoder"
}

/** Property names for [SDLTrack.play] options. */
object SDLMixerPlayProp {
    const val LOOPS_NUMBER = "SDL_mixer.play.loops"
    const val MAX_FRAME_NUMBER = "SDL_mixer.play.max_frame"
    const val MAX_MILLISECONDS_NUMBER = "SDL_mixer.play.max_milliseconds"
    const val START_FRAME_NUMBER = "SDL_mixer.play.start_frame"
    const val START_MILLISECOND_NUMBER = "SDL_mixer.play.start_millisecond"
    const val START_ORDER_NUMBER = "SDL_mixer.play.start_order"
    const val LOOP_START_FRAME_NUMBER = "SDL_mixer.play.loop_start_frame"
    const val LOOP_START_MILLISECOND_NUMBER = "SDL_mixer.play.loop_start_millisecond"
    const val FADE_IN_FRAMES_NUMBER = "SDL_mixer.play.fade_in_frames"
    const val FADE_IN_MILLISECONDS_NUMBER = "SDL_mixer.play.fade_in_milliseconds"
    const val FADE_IN_START_GAIN_FLOAT = "SDL_mixer.play.fade_in_start_gain"
    const val APPEND_SILENCE_FRAMES_NUMBER = "SDL_mixer.play.append_silence_frames"
    const val APPEND_SILENCE_MILLISECONDS_NUMBER = "SDL_mixer.play.append_silence_milliseconds"
    const val HALT_WHEN_EXHAUSTED_BOOLEAN = "SDL_mixer.play.halt_when_exhausted"
}

/** Metadata property names readable through [SDLAudio.properties]. */
object SDLMixerMetadata {
    const val TITLE_STRING = "SDL_mixer.metadata.title"
    const val ARTIST_STRING = "SDL_mixer.metadata.artist"
    const val ALBUM_STRING = "SDL_mixer.metadata.album"
    const val COPYRIGHT_STRING = "SDL_mixer.metadata.copyright"
    const val TRACK_NUMBER = "SDL_mixer.metadata.track"
    const val TOTAL_TRACKS_NUMBER = "SDL_mixer.metadata.total_tracks"
    const val YEAR_NUMBER = "SDL_mixer.metadata.year"
    const val DURATION_FRAMES_NUMBER = "SDL_mixer.metadata.duration_frames"
    const val DURATION_INFINITE_BOOLEAN = "SDL_mixer.metadata.duration_infinite"
}

/** Special [SDLAudio.duration] values. */
object SDLMixerDuration {
    /** The audio's duration is unknown. */
    const val UNKNOWN = -1L

    /** The audio never runs out of sound to generate. */
    const val INFINITE = -2L
}

// =========================================================================
// Data types
// =========================================================================

/** Per-channel gains for [SDLTrack.setStereo]. */
data class SDLMixerStereoGains(val left: Float, val right: Float)

/** 3D coordinates for [SDLTrack.set3DPosition] (right-handed, like OpenGL). */
data class SDLMixerPoint3D(val x: Float, val y: Float, val z: Float)

// =========================================================================
// Callbacks
// =========================================================================

/** Fires when a [SDLTrack] stops (see [SDLTrack.setStoppedCallback]). */
typealias SDLTrackStoppedCallback = (SDLTrack) -> Unit

/**
 * Fires while a track is mixing. [pcm] holds the float samples (a copy of
 * the mixer's internal buffer; changes are not propagated back). [spec] is
 * always SDL_AUDIO_F32.
 */
typealias SDLTrackMixCallback = (SDLTrack, SDLAudioSpec, FloatArray) -> Unit

/** Fires when a [SDLMixerGroup] has finished mixing (see [SDLMixerGroup.setPostMixCallback]). */
typealias SDLGroupMixCallback = (SDLMixerGroup, SDLAudioSpec, FloatArray) -> Unit

/** Fires when all mixing has completed (see [SDLMixerDevice.setPostMixCallback]). */
typealias SDLPostMixCallback = (SDLMixerDevice, SDLAudioSpec, FloatArray) -> Unit

// =========================================================================
// Objects
// =========================================================================

/**
 * A MIX_Mixer, created with [SDLMixer.createMixerDevice] (plays to an audio
 * device) or [SDLMixer.createMixer] (renders to a memory buffer via
 * [generate]).
 */
interface SDLMixerDevice : AutoCloseable {

    /** The raw MIX_Mixer handle address, or 0 after [close]. */
    val ptr: Long

    /** The audio format the mixer is generating, or null on failure. */
    val format: SDLAudioSpec?

    /** The mixer's master gain (default 1.0f). */
    var gain: Float

    /** The mixer's master frequency ratio (default 1.0f). */
    var frequencyRatio: Float

    /** Locks the mixer's internal mutex (pair with [unlock]). */
    fun lock()

    /** Unlocks the mixer previously locked with [lock]. */
    fun unlock()

    /** Loads the audio file at [path] (see [SDLAudio]). */
    fun loadAudio(path: String, predecode: Boolean = false): SDLAudio

    /**
     * Loads audio from the SDL [io] stream. When [closeIO] is true,
     * [SDLIOStream.close] is called when loading finishes. The stream must
     * be seekable.
     */
    fun loadAudioIO(io: SDLIOStream, predecode: Boolean = false, closeIO: Boolean = false): SDLAudio

    /**
     * Loads compressed audio data directly from memory. The binding keeps
     * [data] alive for the lifetime of the returned [SDLAudio] (no copy is
     * made).
     */
    fun loadAudioNoCopy(data: ByteArray): SDLAudio

    /** Loads audio through a set of properties (see [SDLMixerProp]). */
    fun loadAudioWithProperties(props: ULong): SDLAudio

    /** Loads raw PCM [data] (a copy is made) with the given [spec]. */
    fun loadRawAudio(data: ByteArray, spec: SDLAudioSpec): SDLAudio

    /**
     * Loads raw PCM [data] without making a copy. The binding keeps [data]
     * alive for the lifetime of the returned [SDLAudio].
     */
    fun loadRawAudioNoCopy(data: ByteArray, spec: SDLAudioSpec): SDLAudio

    /** Creates a [SDLAudio] that generates a sinewave of [hz] Hz. */
    fun createSineWaveAudio(hz: Int, amplitude: Float, ms: Long): SDLAudio

    /** Creates a new track on this mixer. */
    fun createTrack(): SDLTrack

    /** Creates a mixing group on this mixer. */
    fun createGroup(): SDLMixerGroup

    /** Plays [audio] from start to finish without any management. */
    fun playAudio(audio: SDLAudio): Boolean

    /** Starts (or restarts) all tracks tagged [tag] with [options]. */
    fun playTag(tag: String, options: ULong = 0u): Boolean

    /** Halt all currently-playing tracks, optionally fading out over [fadeOutMs] milliseconds. */
    fun stopAllTracks(fadeOutMs: Long = 0): Boolean

    /** Halt all tracks with [tag], optionally fading out over [fadeOutMs] milliseconds. */
    fun stopTag(tag: String, fadeOutMs: Long = 0): Boolean

    /** Pauses all currently-playing tracks. */
    fun pauseAllTracks(): Boolean

    /** Pauses all tracks with [tag]. */
    fun pauseTag(tag: String): Boolean

    /** Resumes all currently-paused tracks. */
    fun resumeAllTracks(): Boolean

    /** Resumes all tracks with [tag]. */
    fun resumeTag(tag: String): Boolean

    /** Sets the gain of all tracks tagged [tag]. */
    fun setTagGain(tag: String, gain: Float): Boolean

    /** All tracks tagged [tag] (the returned tracks are owned by the mixer; do not close them). */
    fun taggedTracks(tag: String): List<SDLTrack>

    /**
     * Sets a callback that fires after all mixing has completed with a copy
     * of the final float samples. Pass null to disable.
     */
    fun setPostMixCallback(callback: SDLPostMixCallback?)

    /**
     * Generates audio into [buffer] (mixers created with [SDLMixer.createMixer]
     * only). [buffer] must be a multiple of the sample frame size. Returns
     * the number of bytes of real audio mixed (the rest is silence), or -1 on
     * failure.
     */
    fun generate(buffer: ByteArray): Int

    /** Releases the mixer (all its tracks and groups are destroyed too). */
    override fun close()
}

/**
 * A MIX_Audio: static audio data loaded with [SDLMixerDevice.loadAudio] and
 * friends. Can be shared between mixers; assignment to a track takes an
 * internal reference, so it is safe to [close] while a track is still using
 * it.
 */
interface SDLAudio : AutoCloseable {

    /** The raw MIX_Audio handle address, or 0 after [close]. */
    val ptr: Long

    /** The length of the audio in sample frames, or [SDLMixerDuration] values. */
    val duration: Long

    /** The initial audio format of the data, or null on failure. */
    val format: SDLAudioSpec?

    /** The audio's property store (metadata, see [SDLMixerMetadata]), or 0 on failure. */
    val properties: ULong

    /** Converts [ms] milliseconds to sample frames for this audio's format. */
    fun msToFrames(ms: Long): Long

    /** Converts [frames] sample frames to milliseconds for this audio's format. */
    fun framesToMS(frames: Long): Long

    /** Unrefs the audio (freed when no track uses it anymore). */
    override fun close()
}

/**
 * A MIX_Track: a single source of audio on a [SDLMixerDevice], assigned an
 * input with [setAudio] / [setAudioStream] / [setIOStream].
 */
interface SDLTrack : AutoCloseable {

    /** The raw MIX_Track handle address, or 0 after [close]. */
    val ptr: Long

    /** Whether the track is currently playing (not stopped or paused). */
    val playing: Boolean

    /** Whether the track is currently paused. */
    val paused: Boolean

    /** The track's gain (default 1.0f). */
    var gain: Float

    /** The track's frequency ratio (default 1.0f). */
    var frequencyRatio: Float

    /** The number of pending loops (0 = not looping, -1 = infinite). */
    var loops: Int

    /** The current input position in sample frames, or -1 on error. */
    var playbackPosition: Long

    /** The sample frames remaining to be mixed, or -1 if unknown. */
    val remaining: Long

    /** The fade state: 0 if not fading, > 0 fading in, < 0 fading out. */
    val fadeFrames: Long

    /** The [SDLAudio] assigned with [setAudio], or null. */
    val audio: SDLAudio?

    /** The raw SDL_AudioStream handle assigned with [setAudioStream], or 0. */
    val audioStreamPtr: Long

    /** The track's current tags. */
    val tags: List<String>

    /** Assigns [audio] (or null to remove) as the track's input. */
    fun setAudio(audio: SDLAudio?): Boolean

    /** Assigns an SDL_AudioStream (by raw handle) as the track's input. */
    fun setAudioStream(streamPtr: Long): Boolean

    /** Assigns an SDL_IOStream (seekable audio file) as the track's input. */
    fun setIOStream(io: SDLIOStream, closeIO: Boolean = false): Boolean

    /** Assigns an SDL_IOStream providing raw PCM data in [spec] as the input. */
    fun setRawIOStream(io: SDLIOStream, spec: SDLAudioSpec, closeIO: Boolean = false): Boolean

    /** Adds [tag] to the track. */
    fun tag(tag: String): Boolean

    /** Removes [tag] from the track (null removes all tags). */
    fun untag(tag: String? = null)

    /**
     * Starts (or restarts) mixing the track with the given [options]
     * properties (see [SDLMixerPlayProp]).
     */
    fun play(options: ULong = 0u): Boolean

    /** Halts the track, optionally fading out over [fadeOutFrames] sample frames. */
    fun stop(fadeOutFrames: Long = 0): Boolean

    /** Pauses the track. */
    fun pause(): Boolean

    /** Resumes the track. */
    fun resume(): Boolean

    /** Converts [ms] milliseconds to sample frames for the track's current format. */
    fun msToFrames(ms: Long): Long

    /** Converts [frames] sample frames to milliseconds for the track's current format. */
    fun framesToMS(frames: Long): Long

    /**
     * Reorders/mutes the track's output channels ([chmap] is an array of
     * channel indices, -1 mutes; null resets to default).
     */
    fun setOutputChannelMap(chmap: IntArray?): Boolean

    /** Forces stereo output with optional left/right [gains] panning (null disables spatialization). */
    fun setStereo(gains: SDLMixerStereoGains?): Boolean

    /** Enables 3D positional mixing at [position] (null disables spatialization). */
    fun set3DPosition(position: SDLMixerPoint3D?): Boolean

    /** The track's current 3D position, or (0,0,0) when not positioned. */
    fun get3DPosition(): SDLMixerPoint3D?

    /** Assigns the track to [group] (null moves it to the mixer's default group). */
    fun setGroup(group: SDLMixerGroup?): Boolean

    /** Sets a callback fired when the track stops (null disables). */
    fun setStoppedCallback(callback: SDLTrackStoppedCallback?)

    /** Sets a callback fired on the track's initial decoded (raw) audio (null disables). */
    fun setRawCallback(callback: SDLTrackMixCallback?)

    /** Sets a callback fired after the track's audio has been transformed (null disables). */
    fun setCookedCallback(callback: SDLTrackMixCallback?)

    /** Stops and releases the track. */
    override fun close()
}

/** A MIX_Group: a subset of tracks mixed together before the final output. */
interface SDLMixerGroup : AutoCloseable {

    /** The raw MIX_Group handle address, or 0 after [close]. */
    val ptr: Long

    /** Sets a callback fired when the group has finished mixing (null disables). */
    fun setPostMixCallback(callback: SDLGroupMixCallback?)

    /** Releases the group (tracks are reassigned to the default group). */
    override fun close()
}

/**
 * A MIX_AudioDecoder: decodes an audio file to raw PCM without a mixer
 * (created with [SDLMixer.createAudioDecoder]).
 */
interface SDLAudioDecoder : AutoCloseable {

    /** The raw MIX_AudioDecoder handle address, or 0 after [close]. */
    val ptr: Long

    /** The initial audio format of the file, or null on failure. */
    val format: SDLAudioSpec?

    /**
     * Decodes up to [buffer].size bytes into [buffer] in [spec] format.
     * Returns the number of bytes decoded, 0 at end-of-file, or -1 on error.
     */
    fun decode(buffer: ByteArray, spec: SDLAudioSpec): Int

    /** Releases the decoder. */
    override fun close()
}

// =========================================================================
// The SDL_mixer API
// =========================================================================

/**
 * Kotlin Multiplatform bindings for SDL_mixer 3.x, built on top of sdl-kmp.
 *
 *  - initialization: [init] / [quit], decoders: [numAudioDecoders]
 *  - mixers: [createMixerDevice] plays to an audio device, [createMixer]
 *    generates audio to a memory buffer via [SDLMixerDevice.generate]
 *  - audio: [SDLMixerDevice.loadAudio] and friends return [SDLAudio]s that
 *    can be played on [SDLTrack]s or fire-and-forget with
 *    [SDLMixerDevice.playAudio]
 *  - tracks: assign an input, then [SDLTrack.play] with loop/fade options
 *    from [SDLMixerPlayProp]
 *  - callbacks: track stopped / raw / cooked mix callbacks, group post-mix
 *    and mixer post-mix callbacks receive copies of the float samples
 *  - decoding: [createAudioDecoder] decodes files without a mixer
 *
 * On the JVM the bindings delegate to libsdl_mixer_jni, a self-contained JNI
 * shared library whose SDL3 symbols are resolved at runtime from sdl-kmp's
 * libsdl_jni; on native platforms they delegate to the SDL_mixer static
 * library embedded in the published klib (see the mixer.def cinterop file).
 */
expect object SDLMixer {

    /** The version of the linked SDL_mixer library. */
    fun version(): Int

    /**
     * Initializes SDL_mixer. Must succeed before any other function is
     * called; safe to call multiple times, pair each success with [quit].
     */
    fun init(): Boolean

    /** Deinitializes SDL_mixer (pairs with each successful [init]). */
    fun quit()

    /** The number of audio decoders available. */
    fun numAudioDecoders(): Int

    /** The name of the decoder at [index], or null if [index] is invalid. */
    fun audioDecoder(index: Int): String?

    /** The last error set by SDL_mixer (or SDL), or null if there is none. */
    fun error(): String?

    /** Clears the last error. */
    fun clearError()

    /**
     * Creates a mixer that plays sound directly to an audio device.
     * [deviceId] defaults to [cn.enaium.sdl.SDLAudioDeviceID.DEFAULT_PLAYBACK];
     * [spec] is a hint for the device format (the actual format is available
     * through [SDLMixerDevice.format]).
     */
    fun createMixerDevice(deviceId: Int, spec: SDLAudioSpec? = null): SDLMixerDevice

    /**
     * Creates a mixer that generates audio to a memory buffer (see
     * [SDLMixerDevice.generate]). [spec] is the output format and must be
     * provided.
     */
    fun createMixer(spec: SDLAudioSpec): SDLMixerDevice

    /** Creates an audio decoder for the file at [path] with [props] (may be 0). */
    fun createAudioDecoder(path: String, props: ULong = 0u): SDLAudioDecoder

    /** Creates an audio decoder from the SDL [io] stream with [props] (may be 0). */
    fun createAudioDecoderIO(io: SDLIOStream, closeIO: Boolean = false, props: ULong = 0u): SDLAudioDecoder

    /** Converts [ms] milliseconds to sample frames at [sampleRate]. */
    fun msToFrames(sampleRate: Int, ms: Long): Long

    /** Converts [frames] sample frames to milliseconds at [sampleRate]. */
    fun framesToMS(sampleRate: Int, frames: Long): Long
}
