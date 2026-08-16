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
 * CPU-feature compatibility shims for the JVM target.
 *
 * SDL_mixer's MIX_Init() checks the CPU before enabling its SIMD paths: on
 * x86 it calls SDL_HasSSE(), on ARM SDL_HasNEON(). When SDL3 is NOT compiled
 * into this library (the symbols are resolved at runtime from sdl-kmp's
 * libsdl_jni), the exported symbol set of libsdl_jni may not include these
 * functions, which fails the dlopen on Linux (and the load on Windows).
 *
 * These definitions provide the functions locally. Every x86-64 CPU has SSE
 * and every aarch64 CPU has NEON, so returning true matches reality wherever
 * SDL_mixer's SIMD paths are compiled in. SDL_BUILDING_LIBRARY makes
 * SDL_DECLSPEC expand to dllexport on Windows so the definitions are
 * compatible with the dllimport declarations in the SDL headers.
 */
#define SDL_BUILDING_LIBRARY
#include <SDL3/SDL.h>

#if defined(__x86_64__) || defined(__i386__) || defined(_M_X64) || defined(_M_IX86)
bool SDL_HasSSE(void)
{
    return true;
}
#endif

#if defined(__ARM_NEON) || defined(__ARM_NEON__) || defined(__aarch64__) || defined(_M_ARM64)
bool SDL_HasNEON(void)
{
    return true;
}
#endif
