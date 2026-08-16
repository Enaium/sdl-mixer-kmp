# Included by CMake at the top of SDL's project() call (see
# CMAKE_PROJECT_INCLUDE in jni/CMakeLists.txt), i.e. in the SDL CMake
# directory scope, before its targets are defined.
#
# SDL ships a JNI_OnLoad stub for non-Android platforms
# (src/core/SDL_core_unsupported.c) so that a JVM loading a dynamic SDL3
# finds the entry point. The Windows fallback build of this project links
# SDL3 into libsdl_mixer_jni, which defines its own JNI_OnLoad (JNI_OnLoad in
# jni_mixer.cpp); the stub would collide with it at link time. Marking the
# file HEADER_FILE_ONLY keeps it out of the build.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/src/core/SDL_core_unsupported.c")
    set_source_files_properties(
        ${CMAKE_CURRENT_SOURCE_DIR}/src/core/SDL_core_unsupported.c
        PROPERTIES HEADER_FILE_ONLY TRUE
    )
endif()
