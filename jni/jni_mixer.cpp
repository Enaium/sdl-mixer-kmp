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

/*
 * JNI bridge for the sdl-mixer-kmp JVM target.
 *
 * Every `external fun` on the Kotlin `cn.enaium.sdl.mixer.Jni` object maps 1:1
 * to a `Java_cn_enaium_sdl_mixer_Jni_<name>` function in this file (see the
 * naming convention in sdl-kmp's jni_bridge.h).
 *
 * The library statically links SDL_mixer (with the vendored decoders), and
 * its SDL3 symbols are resolved at runtime from libsdl_jni, the JNI library
 * of the sdl-kmp JVM artifact (see jni/CMakeLists.txt). MIX_Mixer, MIX_Track,
 * MIX_Audio etc. are passed across as opaque 64-bit pointers.
 *
 * Error convention: on failure the MIX functions set SDL's error string
 * (SDL_GetError). [Java_cn_enaium_sdl_mixer_Jni_getError] reads it from the
 * mixer-side copy of SDL3, which is the copy the error was written to.
 *
 * Callbacks: the mixing callbacks (track stopped / raw / cooked, group
 * post-mix, post-mix) fire on the audio device thread. The userdata pointer
 * carries the id of a Kotlin-registered callback (see Jni.kt), and the
 * trampolines below attach the current thread to the JVM (when needed) and
 * dispatch to the static methods on `cn.enaium.sdl.mixer.Jni`, which forward
 * to the registered Kotlin lambdas.
 */

#include <jni.h>
#include <stdint.h>
#include <string>
#include <vector>

#include <SDL3/SDL.h>
#include <SDL3_mixer/SDL_mixer.h>

// JNI entry-point naming macro: every external fun on the Kotlin
// `cn.enaium.sdl.mixer.Jni` object maps to Java_cn.enaium.sdl.mixer_Jni_<name>.
#define MIXJNI_FUNC(ret) extern "C" JNIEXPORT ret JNICALL
#define MIXJNI_NAME(name) Java_cn_enaium_sdl_mixer_Jni_##name

// ---------------------------------------------------------------------------
// Marshaling helpers
// ---------------------------------------------------------------------------

static inline jstring mixer_jni_to_string(JNIEnv *env, const char *s) {
    return s ? env->NewStringUTF(s) : nullptr;
}

// Converts a Kotlin String to UTF-8. GetStringUTFChars() returns Modified
// UTF-8 (surrogate pairs are encoded in CESU-8), which is NOT what SDL_mixer
// expects, so the UTF-16 code units are converted properly (supplementary
// plane characters such as emoji become 4-byte UTF-8 sequences).
static inline std::string mixer_jni_copy_string(JNIEnv *env, jstring s) {
    if (!s) return std::string();
    const jsize len = env->GetStringLength(s);
    if (len == 0) return std::string();
    const jchar *chars = env->GetStringChars(s, nullptr);
    if (!chars) return std::string();
    std::string out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

static inline jintArray mixer_jni_new_jint_array(JNIEnv *env, const std::vector<jint> &values) {
    jintArray arr = env->NewIntArray(static_cast<jsize>(values.size()));
    if (arr && !values.empty()) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(values.size()), values.data());
    }
    return arr;
}

static inline jlongArray mixer_jni_new_jlong_array(JNIEnv *env, const std::vector<jlong> &values) {
    jlongArray arr = env->NewLongArray(static_cast<jsize>(values.size()));
    if (arr && !values.empty()) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(values.size()), values.data());
    }
    return arr;
}

static inline jlong mixer_jni_ptr(const void *p) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(p));
}

static inline MIX_Mixer *mixer_jni_mixer(jlong ptr) {
    return reinterpret_cast<MIX_Mixer *>(static_cast<intptr_t>(ptr));
}

static inline MIX_Audio *mixer_jni_audio(jlong ptr) {
    return reinterpret_cast<MIX_Audio *>(static_cast<intptr_t>(ptr));
}

static inline MIX_Track *mixer_jni_track(jlong ptr) {
    return reinterpret_cast<MIX_Track *>(static_cast<intptr_t>(ptr));
}

static inline MIX_Group *mixer_jni_group(jlong ptr) {
    return reinterpret_cast<MIX_Group *>(static_cast<intptr_t>(ptr));
}

static inline MIX_AudioDecoder *mixer_jni_decoder(jlong ptr) {
    return reinterpret_cast<MIX_AudioDecoder *>(static_cast<intptr_t>(ptr));
}

// The Kotlin SDLAudioSpec is marshaled as three ints (format, channels,
// freq); a negative format marks a null spec pointer.
static inline bool mixer_jni_read_spec(jint format, jint channels, jint freq, SDL_AudioSpec &out) {
    if (format < 0) return false;
    out.format = static_cast<SDL_AudioFormat>(format);
    out.channels = channels;
    out.freq = freq;
    return true;
}

static inline jintArray mixer_jni_write_spec(JNIEnv *env, const SDL_AudioSpec &spec) {
    return mixer_jni_new_jint_array(env, {spec.format, spec.channels, spec.freq});
}

// ---------------------------------------------------------------------------
// Callback dispatch (see the file header for the design)
// ---------------------------------------------------------------------------

static JavaVM *mixer_jni_vm = nullptr;
static jclass mixer_jni_class = nullptr;
static jmethodID mixer_jni_track_stopped = nullptr;
static jmethodID mixer_jni_track_mix = nullptr;
static jmethodID mixer_jni_group_mix = nullptr;
static jmethodID mixer_jni_post_mix = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    mixer_jni_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass("cn/enaium/sdl/mixer/Jni");
    if (!cls) {
        return JNI_ERR;
    }
    mixer_jni_class = static_cast<jclass>(env->NewGlobalRef(cls));
    mixer_jni_track_stopped = env->GetStaticMethodID(mixer_jni_class, "callTrackStopped", "(JJ)V");
    mixer_jni_track_mix = env->GetStaticMethodID(mixer_jni_class, "callTrackMix", "(JJIII[FI)V");
    mixer_jni_group_mix = env->GetStaticMethodID(mixer_jni_class, "callGroupMix", "(JJIII[FI)V");
    mixer_jni_post_mix = env->GetStaticMethodID(mixer_jni_class, "callPostMix", "(JJIII[FI)V");
    if (!mixer_jni_track_stopped || !mixer_jni_track_mix || !mixer_jni_group_mix || !mixer_jni_post_mix) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

// Returns a JNIEnv valid on the current thread, attaching it to the VM when
// needed. The caller must call mixer_jni_detach() exactly when this returns
// true (and only then).
static bool mixer_jni_attach(JNIEnv **env) {
    if (!mixer_jni_vm) return false;
    if (mixer_jni_vm->GetEnv(reinterpret_cast<void **>(env), JNI_VERSION_1_6) == JNI_OK) {
        return false;
    }
    return mixer_jni_vm->AttachCurrentThread(reinterpret_cast<void **>(env), nullptr) == JNI_OK;
}

static void mixer_jni_detach(JNIEnv *env) {
    if (env) {
        mixer_jni_vm->DetachCurrentThread();
    }
}

#define MIXJNI_CALLBACK_STOPPED(NAME, TRAMPOLINE, CLASS, METHOD, FUNC)       \
    static void SDLCALL TRAMPOLINE(void *userdata, FUNC *object) {           \
        JNIEnv *env = nullptr;                                               \
        const bool attached = mixer_jni_attach(&env);                        \
        if (env && mixer_jni_class && METHOD) {                              \
            env->CallStaticVoidMethod(mixer_jni_class, METHOD,               \
                                      reinterpret_cast<jlong>(userdata),     \
                                      mixer_jni_ptr(object));                \
        }                                                                    \
        if (attached) {                                                      \
            mixer_jni_detach(env);                                           \
        }                                                                    \
    }

#define MIXJNI_CALLBACK_MIX(NAME, TRAMPOLINE, CLASS, METHOD, FUNC)           \
    static void SDLCALL TRAMPOLINE(void *userdata, FUNC *object,             \
                                   const SDL_AudioSpec *spec, float *pcm,    \
                                   int samples) {                            \
        JNIEnv *env = nullptr;                                               \
        const bool attached = mixer_jni_attach(&env);                        \
        if (env && mixer_jni_class && METHOD) {                              \
            jfloatArray arr = env->NewFloatArray(samples);                   \
            if (arr) {                                                       \
                env->SetFloatArrayRegion(arr, 0, samples, pcm);              \
                env->CallStaticVoidMethod(mixer_jni_class, METHOD,           \
                                          reinterpret_cast<jlong>(userdata), \
                                          mixer_jni_ptr(object),             \
                                          spec->format, spec->channels,      \
                                          spec->freq, arr, samples);         \
                env->DeleteLocalRef(arr);                                    \
            }                                                                \
        }                                                                    \
        if (attached) {                                                      \
            mixer_jni_detach(env);                                           \
        }                                                                    \
    }

MIXJNI_CALLBACK_STOPPED(trackStopped, mixer_jni_track_stopped_cb, mixer_jni_class, mixer_jni_track_stopped, MIX_Track)
MIXJNI_CALLBACK_MIX(trackMix, mixer_jni_track_mix_cb, mixer_jni_class, mixer_jni_track_mix, MIX_Track)
MIXJNI_CALLBACK_MIX(groupMix, mixer_jni_group_mix_cb, mixer_jni_class, mixer_jni_group_mix, MIX_Group)
MIXJNI_CALLBACK_MIX(postMix, mixer_jni_post_mix_cb, mixer_jni_class, mixer_jni_post_mix, MIX_Mixer)

// ---------------------------------------------------------------------------
// Core
// ---------------------------------------------------------------------------

MIXJNI_FUNC(jint) MIXJNI_NAME(version)(JNIEnv *, jclass) {
    return MIX_Version();
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(init)(JNIEnv *, jclass) {
    return MIX_Init() ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(void) MIXJNI_NAME(quit)(JNIEnv *, jclass) {
    MIX_Quit();
}

MIXJNI_FUNC(jint) MIXJNI_NAME(numAudioDecoders)(JNIEnv *, jclass) {
    return MIX_GetNumAudioDecoders();
}

MIXJNI_FUNC(jstring) MIXJNI_NAME(audioDecoder)(JNIEnv *env, jclass, jint index) {
    return mixer_jni_to_string(env, MIX_GetAudioDecoder(index));
}

MIXJNI_FUNC(jstring) MIXJNI_NAME(getError)(JNIEnv *env, jclass) {
    return mixer_jni_to_string(env, SDL_GetError());
}

MIXJNI_FUNC(void) MIXJNI_NAME(clearError)(JNIEnv *, jclass) {
    SDL_ClearError();
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(msToFrames)(JNIEnv *, jclass, jint sampleRate, jlong ms) {
    return MIX_MSToFrames(sampleRate, ms);
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(framesToMS)(JNIEnv *, jclass, jint sampleRate, jlong frames) {
    return MIX_FramesToMS(sampleRate, frames);
}

// ---------------------------------------------------------------------------
// Mixers
// ---------------------------------------------------------------------------

MIXJNI_FUNC(jlong) MIXJNI_NAME(createMixerDevice)(JNIEnv *, jclass, jint deviceId,
                                                  jint format, jint channels, jint freq) {
    SDL_AudioSpec spec;
    SDL_AudioSpec *specPtr = mixer_jni_read_spec(format, channels, freq, spec) ? &spec : nullptr;
    return mixer_jni_ptr(MIX_CreateMixerDevice(static_cast<SDL_AudioDeviceID>(deviceId), specPtr));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(createMixer)(JNIEnv *, jclass, jint format, jint channels, jint freq) {
    SDL_AudioSpec spec;
    if (!mixer_jni_read_spec(format, channels, freq, spec)) {
        return 0;
    }
    return mixer_jni_ptr(MIX_CreateMixer(&spec));
}

MIXJNI_FUNC(void) MIXJNI_NAME(destroyMixer)(JNIEnv *, jclass, jlong mixer) {
    MIX_DestroyMixer(mixer_jni_mixer(mixer));
}

MIXJNI_FUNC(jintArray) MIXJNI_NAME(getMixerFormat)(JNIEnv *env, jclass, jlong mixer) {
    SDL_AudioSpec spec;
    if (!MIX_GetMixerFormat(mixer_jni_mixer(mixer), &spec)) {
        return nullptr;
    }
    return mixer_jni_write_spec(env, spec);
}

MIXJNI_FUNC(void) MIXJNI_NAME(lockMixer)(JNIEnv *, jclass, jlong mixer) {
    MIX_LockMixer(mixer_jni_mixer(mixer));
}

MIXJNI_FUNC(void) MIXJNI_NAME(unlockMixer)(JNIEnv *, jclass, jlong mixer) {
    MIX_UnlockMixer(mixer_jni_mixer(mixer));
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setMixerGain)(JNIEnv *, jclass, jlong mixer, jfloat gain) {
    return MIX_SetMixerGain(mixer_jni_mixer(mixer), gain) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jfloat) MIXJNI_NAME(getMixerGain)(JNIEnv *, jclass, jlong mixer) {
    return MIX_GetMixerGain(mixer_jni_mixer(mixer));
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setMixerFrequencyRatio)(JNIEnv *, jclass, jlong mixer, jfloat ratio) {
    return MIX_SetMixerFrequencyRatio(mixer_jni_mixer(mixer), ratio) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jfloat) MIXJNI_NAME(getMixerFrequencyRatio)(JNIEnv *, jclass, jlong mixer) {
    return MIX_GetMixerFrequencyRatio(mixer_jni_mixer(mixer));
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(playAudio)(JNIEnv *, jclass, jlong mixer, jlong audio) {
    return MIX_PlayAudio(mixer_jni_mixer(mixer), mixer_jni_audio(audio)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(playTag)(JNIEnv *env, jclass, jlong mixer, jstring tag, jint options) {
    std::string t = mixer_jni_copy_string(env, tag);
    return MIX_PlayTag(mixer_jni_mixer(mixer), t.c_str(), static_cast<SDL_PropertiesID>(options)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(stopAllTracks)(JNIEnv *, jclass, jlong mixer, jlong fadeOutMs) {
    return MIX_StopAllTracks(mixer_jni_mixer(mixer), fadeOutMs) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(stopTag)(JNIEnv *env, jclass, jlong mixer, jstring tag, jlong fadeOutMs) {
    std::string t = mixer_jni_copy_string(env, tag);
    return MIX_StopTag(mixer_jni_mixer(mixer), t.c_str(), fadeOutMs) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(pauseAllTracks)(JNIEnv *, jclass, jlong mixer) {
    return MIX_PauseAllTracks(mixer_jni_mixer(mixer)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(pauseTag)(JNIEnv *env, jclass, jlong mixer, jstring tag) {
    std::string t = mixer_jni_copy_string(env, tag);
    return MIX_PauseTag(mixer_jni_mixer(mixer), t.c_str()) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(resumeAllTracks)(JNIEnv *, jclass, jlong mixer) {
    return MIX_ResumeAllTracks(mixer_jni_mixer(mixer)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(resumeTag)(JNIEnv *env, jclass, jlong mixer, jstring tag) {
    std::string t = mixer_jni_copy_string(env, tag);
    return MIX_ResumeTag(mixer_jni_mixer(mixer), t.c_str()) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTagGain)(JNIEnv *env, jclass, jlong mixer, jstring tag, jfloat gain) {
    std::string t = mixer_jni_copy_string(env, tag);
    return MIX_SetTagGain(mixer_jni_mixer(mixer), t.c_str(), gain) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jlongArray) MIXJNI_NAME(getTaggedTracks)(JNIEnv *env, jclass, jlong mixer, jstring tag) {
    std::string t = mixer_jni_copy_string(env, tag);
    int count = 0;
    MIX_Track **tracks = MIX_GetTaggedTracks(mixer_jni_mixer(mixer), t.c_str(), &count);
    if (!tracks) {
        return nullptr;
    }
    std::vector<jlong> out;
    out.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        out.push_back(mixer_jni_ptr(tracks[i]));
    }
    SDL_free(tracks);
    return mixer_jni_new_jlong_array(env, out);
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setPostMixCallback)(JNIEnv *, jclass, jlong mixer, jlong id) {
    return MIX_SetPostMixCallback(mixer_jni_mixer(mixer),
                                  id ? mixer_jni_post_mix_cb : nullptr,
                                  id ? reinterpret_cast<void *>(id) : nullptr) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jint) MIXJNI_NAME(generate)(JNIEnv *env, jclass, jlong mixer, jbyteArray buffer) {
    jsize len = env->GetArrayLength(buffer);
    jbyte *data = env->GetByteArrayElements(buffer, nullptr);
    const int ret = MIX_Generate(mixer_jni_mixer(mixer), data, static_cast<int>(len));
    env->ReleaseByteArrayElements(buffer, data, 0);
    return ret;
}

// ---------------------------------------------------------------------------
// Audio
// ---------------------------------------------------------------------------

MIXJNI_FUNC(jlong) MIXJNI_NAME(loadAudio)(JNIEnv *env, jclass, jlong mixer, jstring path, jboolean predecode) {
    std::string p = mixer_jni_copy_string(env, path);
    return mixer_jni_ptr(MIX_LoadAudio(mixer_jni_mixer(mixer), p.c_str(), predecode == JNI_TRUE));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(loadAudioIO)(JNIEnv *, jclass, jlong mixer, jlong io,
                                            jboolean predecode, jboolean closeio) {
    auto *stream = reinterpret_cast<SDL_IOStream *>(static_cast<intptr_t>(io));
    return mixer_jni_ptr(MIX_LoadAudio_IO(mixer_jni_mixer(mixer), stream, predecode == JNI_TRUE, closeio == JNI_TRUE));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(loadAudioNoCopy)(JNIEnv *env, jclass, jlong mixer, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte *src = env->GetByteArrayElements(data, nullptr);
    // The JVM side cannot hand SDL a pointer to JVM-owned memory that would
    // outlive the call, so the data is copied to SDL-owned memory; SDL frees
    // it when the MIX_Audio is destroyed.
    void *buf = SDL_malloc(static_cast<size_t>(len));
    if (!buf) {
        env->ReleaseByteArrayElements(data, src, JNI_ABORT);
        SDL_SetError("Out of memory");
        return 0;
    }
    if (len > 0) {
        SDL_memcpy(buf, src, static_cast<size_t>(len));
    }
    env->ReleaseByteArrayElements(data, src, JNI_ABORT);
    return mixer_jni_ptr(MIX_LoadAudioNoCopy(mixer_jni_mixer(mixer), buf, static_cast<size_t>(len), true));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(loadAudioWithProperties)(JNIEnv *, jclass, jint props) {
    return mixer_jni_ptr(MIX_LoadAudioWithProperties(static_cast<SDL_PropertiesID>(props)));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(loadRawAudio)(JNIEnv *env, jclass, jlong mixer, jbyteArray data,
                                             jint format, jint channels, jint freq) {
    SDL_AudioSpec spec;
    if (!mixer_jni_read_spec(format, channels, freq, spec)) {
        return 0;
    }
    jsize len = env->GetArrayLength(data);
    jbyte *src = env->GetByteArrayElements(data, nullptr);
    jlong audio = mixer_jni_ptr(MIX_LoadRawAudio(mixer_jni_mixer(mixer), src, static_cast<size_t>(len), &spec));
    env->ReleaseByteArrayElements(data, src, JNI_ABORT);
    return audio;
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(loadRawAudioNoCopy)(JNIEnv *env, jclass, jlong mixer, jbyteArray data,
                                                   jint format, jint channels, jint freq) {
    SDL_AudioSpec spec;
    if (!mixer_jni_read_spec(format, channels, freq, spec)) {
        return 0;
    }
    jsize len = env->GetArrayLength(data);
    jbyte *src = env->GetByteArrayElements(data, nullptr);
    // Same as loadAudioNoCopy: copy into SDL-owned memory that SDL frees when
    // the MIX_Audio is destroyed.
    void *buf = SDL_malloc(static_cast<size_t>(len));
    if (!buf) {
        env->ReleaseByteArrayElements(data, src, JNI_ABORT);
        SDL_SetError("Out of memory");
        return 0;
    }
    if (len > 0) {
        SDL_memcpy(buf, src, static_cast<size_t>(len));
    }
    env->ReleaseByteArrayElements(data, src, JNI_ABORT);
    return mixer_jni_ptr(MIX_LoadRawAudioNoCopy(mixer_jni_mixer(mixer), buf, static_cast<size_t>(len), &spec, true));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(createSineWaveAudio)(JNIEnv *, jclass, jlong mixer, jint hz,
                                                    jfloat amplitude, jlong ms) {
    return mixer_jni_ptr(MIX_CreateSineWaveAudio(mixer_jni_mixer(mixer), hz, amplitude, ms));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(getAudioDuration)(JNIEnv *, jclass, jlong audio) {
    return MIX_GetAudioDuration(mixer_jni_audio(audio));
}

MIXJNI_FUNC(jintArray) MIXJNI_NAME(getAudioFormat)(JNIEnv *env, jclass, jlong audio) {
    SDL_AudioSpec spec;
    if (!MIX_GetAudioFormat(mixer_jni_audio(audio), &spec)) {
        return nullptr;
    }
    return mixer_jni_write_spec(env, spec);
}

MIXJNI_FUNC(jint) MIXJNI_NAME(getAudioProperties)(JNIEnv *, jclass, jlong audio) {
    return static_cast<jint>(MIX_GetAudioProperties(mixer_jni_audio(audio)));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(audioMSToFrames)(JNIEnv *, jclass, jlong audio, jlong ms) {
    return MIX_AudioMSToFrames(mixer_jni_audio(audio), ms);
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(audioFramesToMS)(JNIEnv *, jclass, jlong audio, jlong frames) {
    return MIX_AudioFramesToMS(mixer_jni_audio(audio), frames);
}

MIXJNI_FUNC(void) MIXJNI_NAME(destroyAudio)(JNIEnv *, jclass, jlong audio) {
    MIX_DestroyAudio(mixer_jni_audio(audio));
}

// ---------------------------------------------------------------------------
// Tracks
// ---------------------------------------------------------------------------

MIXJNI_FUNC(jlong) MIXJNI_NAME(createTrack)(JNIEnv *, jclass, jlong mixer) {
    return mixer_jni_ptr(MIX_CreateTrack(mixer_jni_mixer(mixer)));
}

MIXJNI_FUNC(void) MIXJNI_NAME(destroyTrack)(JNIEnv *, jclass, jlong track) {
    MIX_DestroyTrack(mixer_jni_track(track));
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackAudio)(JNIEnv *, jclass, jlong track, jlong audio) {
    return MIX_SetTrackAudio(mixer_jni_track(track), mixer_jni_audio(audio)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackAudioStream)(JNIEnv *, jclass, jlong track, jlong stream) {
    auto *s = reinterpret_cast<SDL_AudioStream *>(static_cast<intptr_t>(stream));
    return MIX_SetTrackAudioStream(mixer_jni_track(track), s) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackIOStream)(JNIEnv *, jclass, jlong track, jlong io, jboolean closeio) {
    auto *stream = reinterpret_cast<SDL_IOStream *>(static_cast<intptr_t>(io));
    return MIX_SetTrackIOStream(mixer_jni_track(track), stream, closeio == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackRawIOStream)(JNIEnv *, jclass, jlong track, jlong io,
                                                       jint format, jint channels, jint freq, jboolean closeio) {
    SDL_AudioSpec spec;
    if (!mixer_jni_read_spec(format, channels, freq, spec)) {
        return JNI_FALSE;
    }
    auto *stream = reinterpret_cast<SDL_IOStream *>(static_cast<intptr_t>(io));
    return MIX_SetTrackRawIOStream(mixer_jni_track(track), stream, &spec, closeio == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(tagTrack)(JNIEnv *env, jclass, jlong track, jstring tag) {
    std::string t = mixer_jni_copy_string(env, tag);
    return MIX_TagTrack(mixer_jni_track(track), t.c_str()) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(void) MIXJNI_NAME(untagTrack)(JNIEnv *env, jclass, jlong track, jstring tag) {
    std::string t = mixer_jni_copy_string(env, tag);
    MIX_UntagTrack(mixer_jni_track(track), t.empty() ? nullptr : t.c_str());
}

MIXJNI_FUNC(jobjectArray) MIXJNI_NAME(getTrackTags)(JNIEnv *env, jclass, jlong track) {
    int count = 0;
    char **tags = MIX_GetTrackTags(mixer_jni_track(track), &count);
    if (!tags) {
        return nullptr;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(count, stringClass, nullptr);
    for (int i = 0; i < count; ++i) {
        env->SetObjectArrayElement(out, i, env->NewStringUTF(tags[i]));
    }
    SDL_free(tags);
    return out;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackPlaybackPosition)(JNIEnv *, jclass, jlong track, jlong frames) {
    return MIX_SetTrackPlaybackPosition(mixer_jni_track(track), frames) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(getTrackPlaybackPosition)(JNIEnv *, jclass, jlong track) {
    return MIX_GetTrackPlaybackPosition(mixer_jni_track(track));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(getTrackFadeFrames)(JNIEnv *, jclass, jlong track) {
    return MIX_GetTrackFadeFrames(mixer_jni_track(track));
}

MIXJNI_FUNC(jint) MIXJNI_NAME(getTrackLoops)(JNIEnv *, jclass, jlong track) {
    return MIX_GetTrackLoops(mixer_jni_track(track));
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackLoops)(JNIEnv *, jclass, jlong track, jint loops) {
    return MIX_SetTrackLoops(mixer_jni_track(track), loops) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(getTrackAudio)(JNIEnv *, jclass, jlong track) {
    return mixer_jni_ptr(MIX_GetTrackAudio(mixer_jni_track(track)));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(getTrackAudioStream)(JNIEnv *, jclass, jlong track) {
    return mixer_jni_ptr(MIX_GetTrackAudioStream(mixer_jni_track(track)));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(getTrackRemaining)(JNIEnv *, jclass, jlong track) {
    return MIX_GetTrackRemaining(mixer_jni_track(track));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(trackMSToFrames)(JNIEnv *, jclass, jlong track, jlong ms) {
    return MIX_TrackMSToFrames(mixer_jni_track(track), ms);
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(trackFramesToMS)(JNIEnv *, jclass, jlong track, jlong frames) {
    return MIX_TrackFramesToMS(mixer_jni_track(track), frames);
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(playTrack)(JNIEnv *, jclass, jlong track, jint options) {
    return MIX_PlayTrack(mixer_jni_track(track), static_cast<SDL_PropertiesID>(options)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(stopTrack)(JNIEnv *, jclass, jlong track, jlong fadeOutFrames) {
    return MIX_StopTrack(mixer_jni_track(track), fadeOutFrames) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(pauseTrack)(JNIEnv *, jclass, jlong track) {
    return MIX_PauseTrack(mixer_jni_track(track)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(resumeTrack)(JNIEnv *, jclass, jlong track) {
    return MIX_ResumeTrack(mixer_jni_track(track)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(trackPlaying)(JNIEnv *, jclass, jlong track) {
    return MIX_TrackPlaying(mixer_jni_track(track)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(trackPaused)(JNIEnv *, jclass, jlong track) {
    return MIX_TrackPaused(mixer_jni_track(track)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackGain)(JNIEnv *, jclass, jlong track, jfloat gain) {
    return MIX_SetTrackGain(mixer_jni_track(track), gain) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jfloat) MIXJNI_NAME(getTrackGain)(JNIEnv *, jclass, jlong track) {
    return MIX_GetTrackGain(mixer_jni_track(track));
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackFrequencyRatio)(JNIEnv *, jclass, jlong track, jfloat ratio) {
    return MIX_SetTrackFrequencyRatio(mixer_jni_track(track), ratio) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jfloat) MIXJNI_NAME(getTrackFrequencyRatio)(JNIEnv *, jclass, jlong track) {
    return MIX_GetTrackFrequencyRatio(mixer_jni_track(track));
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackOutputChannelMap)(JNIEnv *env, jclass, jlong track, jintArray chmap) {
    jint *elems = nullptr;
    jsize count = 0;
    if (chmap) {
        count = env->GetArrayLength(chmap);
        elems = env->GetIntArrayElements(chmap, nullptr);
    }
    const bool ok = MIX_SetTrackOutputChannelMap(mixer_jni_track(track), elems, static_cast<int>(count));
    if (elems) {
        env->ReleaseIntArrayElements(chmap, elems, JNI_ABORT);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackStereo)(JNIEnv *, jclass, jlong track, jboolean hasGains,
                                                  jfloat left, jfloat right) {
    MIX_StereoGains gains;
    MIX_StereoGains *gainsPtr = nullptr;
    if (hasGains == JNI_TRUE) {
        gains.left = left;
        gains.right = right;
        gainsPtr = &gains;
    }
    return MIX_SetTrackStereo(mixer_jni_track(track), gainsPtr) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrack3DPosition)(JNIEnv *, jclass, jlong track, jboolean hasPosition,
                                                      jfloat x, jfloat y, jfloat z) {
    MIX_Point3D pos;
    MIX_Point3D *posPtr = nullptr;
    if (hasPosition == JNI_TRUE) {
        pos.x = x;
        pos.y = y;
        pos.z = z;
        posPtr = &pos;
    }
    return MIX_SetTrack3DPosition(mixer_jni_track(track), posPtr) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jfloatArray) MIXJNI_NAME(getTrack3DPosition)(JNIEnv *env, jclass, jlong track) {
    MIX_Point3D pos;
    if (!MIX_GetTrack3DPosition(mixer_jni_track(track), &pos)) {
        return nullptr;
    }
    jfloatArray arr = env->NewFloatArray(3);
    if (arr) {
        jfloat values[3] = {pos.x, pos.y, pos.z};
        env->SetFloatArrayRegion(arr, 0, 3, values);
    }
    return arr;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackGroup)(JNIEnv *, jclass, jlong track, jlong group) {
    return MIX_SetTrackGroup(mixer_jni_track(track), mixer_jni_group(group)) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackStoppedCallback)(JNIEnv *, jclass, jlong track, jlong id) {
    return MIX_SetTrackStoppedCallback(mixer_jni_track(track),
                                       id ? mixer_jni_track_stopped_cb : nullptr,
                                       id ? reinterpret_cast<void *>(id) : nullptr) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackRawCallback)(JNIEnv *, jclass, jlong track, jlong id) {
    return MIX_SetTrackRawCallback(mixer_jni_track(track),
                                   id ? mixer_jni_track_mix_cb : nullptr,
                                   id ? reinterpret_cast<void *>(id) : nullptr) ? JNI_TRUE : JNI_FALSE;
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setTrackCookedCallback)(JNIEnv *, jclass, jlong track, jlong id) {
    return MIX_SetTrackCookedCallback(mixer_jni_track(track),
                                      id ? mixer_jni_track_mix_cb : nullptr,
                                      id ? reinterpret_cast<void *>(id) : nullptr) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

MIXJNI_FUNC(jlong) MIXJNI_NAME(createGroup)(JNIEnv *, jclass, jlong mixer) {
    return mixer_jni_ptr(MIX_CreateGroup(mixer_jni_mixer(mixer)));
}

MIXJNI_FUNC(void) MIXJNI_NAME(destroyGroup)(JNIEnv *, jclass, jlong group) {
    MIX_DestroyGroup(mixer_jni_group(group));
}

MIXJNI_FUNC(jint) MIXJNI_NAME(getGroupProperties)(JNIEnv *, jclass, jlong group) {
    return static_cast<jint>(MIX_GetGroupProperties(mixer_jni_group(group)));
}

MIXJNI_FUNC(jboolean) MIXJNI_NAME(setGroupPostMixCallback)(JNIEnv *, jclass, jlong group, jlong id) {
    return MIX_SetGroupPostMixCallback(mixer_jni_group(group),
                                       id ? mixer_jni_group_mix_cb : nullptr,
                                       id ? reinterpret_cast<void *>(id) : nullptr) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Audio decoders
// ---------------------------------------------------------------------------

MIXJNI_FUNC(jlong) MIXJNI_NAME(createAudioDecoder)(JNIEnv *env, jclass, jstring path, jint props) {
    std::string p = mixer_jni_copy_string(env, path);
    return mixer_jni_ptr(MIX_CreateAudioDecoder(p.c_str(), static_cast<SDL_PropertiesID>(props)));
}

MIXJNI_FUNC(jlong) MIXJNI_NAME(createAudioDecoderIO)(JNIEnv *, jclass, jlong io, jboolean closeio, jint props) {
    auto *stream = reinterpret_cast<SDL_IOStream *>(static_cast<intptr_t>(io));
    return mixer_jni_ptr(MIX_CreateAudioDecoder_IO(stream, closeio == JNI_TRUE, static_cast<SDL_PropertiesID>(props)));
}

MIXJNI_FUNC(void) MIXJNI_NAME(destroyAudioDecoder)(JNIEnv *, jclass, jlong decoder) {
    MIX_DestroyAudioDecoder(mixer_jni_decoder(decoder));
}

MIXJNI_FUNC(jintArray) MIXJNI_NAME(getAudioDecoderFormat)(JNIEnv *env, jclass, jlong decoder) {
    SDL_AudioSpec spec;
    if (!MIX_GetAudioDecoderFormat(mixer_jni_decoder(decoder), &spec)) {
        return nullptr;
    }
    return mixer_jni_write_spec(env, spec);
}

MIXJNI_FUNC(jint) MIXJNI_NAME(decodeAudio)(JNIEnv *env, jclass, jlong decoder, jbyteArray buffer,
                                           jint format, jint channels, jint freq) {
    SDL_AudioSpec spec;
    if (!mixer_jni_read_spec(format, channels, freq, spec)) {
        return -1;
    }
    jsize len = env->GetArrayLength(buffer);
    jbyte *data = env->GetByteArrayElements(buffer, nullptr);
    const int ret = MIX_DecodeAudio(mixer_jni_decoder(decoder), data, static_cast<int>(len), &spec);
    env->ReleaseByteArrayElements(buffer, data, 0);
    return ret;
}
