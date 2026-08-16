# Copyright (c) 2026 Enaium
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

# Builds SDL_mixer (with the vendored decoders: dr_mp3, dr_flac, stb_vorbis,
# timidity, ...) as a static library for the Kotlin/Native targets, and copies
# it to SDLMIXER_KMP_OUTPUT_DIR/libSDL3_mixer.a where the mixer.def cinterop
# embeds it into the produced klib with -staticLibrary.
#
# SDL3 itself is NOT compiled: SDL_mixer only needs the SDL3 headers, and the
# SDL3 symbols are resolved at the consumer's final link from libSDL3.a, which
# the sdl-kmp klib embeds (the consumer always links it, since the
# sdl-mixer-kmp bindings use the cn.enaium.sdl types).
cmake_minimum_required(VERSION 3.21)
project(sdl-mixer-kmp-native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT DEFINED SDL_SOURCE_DIR)
    set(SDL_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../includes/SDL")
endif()
if(NOT DEFINED SDLMIXER_SOURCE_DIR)
    set(SDLMIXER_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../includes/SDL_mixer")
endif()
if(NOT DEFINED SDLMIXER_KMP_OUTPUT_DIR)
    set(SDLMIXER_KMP_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/out")
endif()

# Fake SDL3 targets so SDL_mixer's CMake configures without building SDL3.
# SDL3::Headers provides the include directory; SDL3::SDL3 is never linked
# (SDL_mixer's static build embeds only its own objects plus the vendored
# decoder objects).
add_library(SDL3::Headers INTERFACE IMPORTED)
set_target_properties(SDL3::Headers PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${SDL_SOURCE_DIR}/include")
add_library(SDL3::SDL3 INTERFACE IMPORTED)

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

# Copy the merged archive (SDL_mixer + vendored decoder objects) into the
# output directory consumed by the cinterop task.
set(SDLMIXER_KMP_MERGED_ARCHIVE "${SDLMIXER_KMP_OUTPUT_DIR}/libSDL3_mixer.a")
add_custom_command(
    OUTPUT ${SDLMIXER_KMP_MERGED_ARCHIVE}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${SDLMIXER_KMP_OUTPUT_DIR}
    COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:SDL3_mixer-static> ${SDLMIXER_KMP_MERGED_ARCHIVE}
    DEPENDS SDL3_mixer-static
    COMMENT "Copying SDL3_mixer static library for Kotlin/Native"
    COMMAND_EXPAND_LISTS
)
add_custom_target(sdl-mixer-kmp-merged ALL DEPENDS ${SDLMIXER_KMP_MERGED_ARCHIVE})
