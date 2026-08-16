cmake_minimum_required(VERSION 3.16)

project(sdl-mixer-kmp-jni C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Builds the JNI shared library (libsdl_mixer_jni) for the JVM target.
#
# SDL3 is NOT linked: a single SDL3 instance per process is required, because
# SDL3 validates its objects with magic numbers against its own object
# registry - a second copy would reject every object created by the first.
# SDL_mixer (with the vendored decoders: dr_mp3, dr_flac, stb_vorbis,
# timidity, ...) is compiled statically, and its SDL3 symbols are resolved at
# runtime from libsdl_jni, the JNI library of the sdl-kmp JVM artifact:
#
#  - macOS/Linux: the symbols stay undefined in libsdl_mixer_jni and are
#    resolved at dlopen time from libsdl_jni, which exports them (SDL3 is
#    compiled with default visibility there). libsdl_jni must therefore be
#    loaded first; the Kotlin Jni object enforces this.
#  - Windows: MinGW's linker refuses undefined symbols in DLLs, so the
#    build links the actual libsdl_jni.dll (extracted from the sdl-kmp JVM
#    artifact by the Gradle build and passed as SDL_JNI_DLL): its exports
#    become import entries and resolve at load time. When SDL_JNI_DLL is not
#    provided (local developer builds without the sdl-kmp jar), SDL3 is
#    compiled and linked in as a fallback (two copies: works for standalone
#    use, but cross-copy SDL3 objects are rejected).
if(NOT DEFINED SDL_SOURCE_DIR)
    set(SDL_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../includes/SDL")
endif()
if(NOT DEFINED SDLMIXER_SOURCE_DIR)
    set(SDLMIXER_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../includes/SDL_mixer")
endif()

if(WIN32 AND DEFINED SDL_JNI_DLL AND EXISTS "${SDL_JNI_DLL}")
    # SDL3::Headers provides the include directory; SDL3::SDL3 is only used
    # by SDL_mixer's CMake for its find_package check.
    add_library(SDL3::Headers INTERFACE IMPORTED)
    set_target_properties(SDL3::Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${SDL_SOURCE_DIR}/include")
    add_library(SDL3::SDL3 INTERFACE IMPORTED)
else()
    # Fake SDL3 targets so SDL_mixer's CMake configures without building SDL3.
    add_library(SDL3::Headers INTERFACE IMPORTED)
    set_target_properties(SDL3::Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${SDL_SOURCE_DIR}/include")
    add_library(SDL3::SDL3 INTERFACE IMPORTED)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SDLMIXER_VENDORED ON CACHE BOOL "" FORCE)
set(SDLMIXER_INSTALL OFF CACHE BOOL "" FORCE)
set(SDLMIXER_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SDLMIXER_TESTS OFF CACHE BOOL "" FORCE)
# Only the decoders implemented in SDL_mixer itself (or bundled in src/) are
# enabled: WAV/AIFF/VOC/AU, FLAC via dr_flac, MP3 via dr_mp3, Ogg Vorbis via
# stb_vorbis and MIDI via timidity. The other formats need SDL_mixer's
# external submodules (ogg/vorbis/opus/flac/mpg123/fluidsynth/gme/xmp/
# wavpack), which would force initializing nested submodules for every build;
# see SDL_mixer's CMakeLists.txt for the full option list.
set(SDLMIXER_FLAC_LIBFLAC OFF CACHE BOOL "" FORCE)
set(SDLMIXER_GME OFF CACHE BOOL "" FORCE)
set(SDLMIXER_MOD OFF CACHE BOOL "" FORCE)
set(SDLMIXER_MP3_MPG123 OFF CACHE BOOL "" FORCE)
set(SDLMIXER_MIDI_FLUIDSYNTH OFF CACHE BOOL "" FORCE)
set(SDLMIXER_OPUS OFF CACHE BOOL "" FORCE)
set(SDLMIXER_VORBIS_VORBISFILE OFF CACHE BOOL "" FORCE)
set(SDLMIXER_WAVPACK OFF CACHE BOOL "" FORCE)

add_subdirectory(${SDLMIXER_SOURCE_DIR} SDL_mixer)

add_library(sdl_mixer_jni SHARED
    jni_mixer.cpp
)
set_target_properties(sdl_mixer_jni PROPERTIES OUTPUT_NAME sdl_mixer_jni)

# SDL_mixer.h redefines main on Windows (via SDL_main.h); this is a library,
# not an executable, so tell SDL the main() handling is the application's
# business.
target_compile_definitions(sdl_mixer_jni PRIVATE SDL_MAIN_HANDLED)

target_include_directories(sdl_mixer_jni PRIVATE
    ${SDL_SOURCE_DIR}/include
    ${SDLMIXER_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if(JNI_INCLUDE_DIR)
    target_include_directories(sdl_mixer_jni PRIVATE ${JNI_INCLUDE_DIR})
else()
    find_package(JNI REQUIRED)
    target_include_directories(sdl_mixer_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
if(JNI_INCLUDE_DIR_PLATFORM)
    target_include_directories(sdl_mixer_jni PRIVATE ${JNI_INCLUDE_DIR_PLATFORM})
endif()

# SDL3_mixer-static already contains the vendored decoder objects, and the
# archive is referenced by file path (not as a target) so SDL3 stays out of
# the link.
target_link_libraries(sdl_mixer_jni PRIVATE $<TARGET_FILE:SDL3_mixer-static>)
add_dependencies(sdl_mixer_jni SDL3_mixer-static)

if(WIN32 AND DEFINED SDL_JNI_DLL AND EXISTS "${SDL_JNI_DLL}")
    # Linking the DLL file itself makes ld build import entries for its
    # exports (the SDL3 symbols), resolving the undefined references.
    target_link_libraries(sdl_mixer_jni PRIVATE "${SDL_JNI_DLL}")
elseif(APPLE)
    # Resolve the SDL3 symbols at runtime from libsdl_jni (loaded first, see
    # Jni.kt) instead of failing the link.
    target_link_options(sdl_mixer_jni PRIVATE -Wl,-undefined,dynamic_lookup)
elseif(UNIX AND NOT APPLE)
    # GNU ld allows undefined symbols in shared libraries by default; the
    # SDL3 symbols are resolved from libsdl_jni at dlopen time.
    target_link_libraries(sdl_mixer_jni PRIVATE -lm -ldl -lpthread)
elseif(WIN32)
    # Fallback (no SDL_JNI_DLL provided): compile and link SDL3 itself.
    set(SDL_SHARED OFF CACHE BOOL "" FORCE)
    set(SDL_STATIC ON CACHE BOOL "" FORCE)
    set(SDL_TEST OFF CACHE BOOL "" FORCE)
    set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(SDL_TESTS OFF CACHE BOOL "" FORCE)
    set(SDL_INSTALL OFF CACHE BOOL "" FORCE)
    add_subdirectory(${SDL_SOURCE_DIR} SDL)
    target_link_libraries(sdl_mixer_jni PRIVATE SDL3-static)
endif()

if(WIN32)
    set_target_properties(sdl_mixer_jni PROPERTIES PREFIX "")
endif()
