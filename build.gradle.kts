plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "cn.enaium.sdl"
    // <kmp-module-version>-fixed.<pinned upstream version>. The KMP module
    // version moves independently; the suffix records the SDL_mixer release it
    // binds. Keep the suffix in sync with includes/SDL_mixer.
    version = "1.0.1-fixed.3.2.4"
}
