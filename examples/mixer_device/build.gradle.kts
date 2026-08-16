import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    macosArm64 {
        binaries.executable()
    }

    macosX64 {
        binaries.executable()
    }

    linuxX64 {
        binaries.executable()
    }

    mingwX64 {
        binaries.executable()
    }

    sourceSets {
        // Kotlin 2.4's default hierarchy template does not create nativeMain
        // automatically; declare it and attach the native targets.
        val nativeMain = create("nativeMain") {
            dependsOn(getByName("commonMain"))
        }
        macosArm64Main {
            dependsOn(nativeMain)
        }
        macosX64Main {
            dependsOn(nativeMain)
        }
        linuxX64Main {
            dependsOn(nativeMain)
        }
        mingwX64Main {
            dependsOn(nativeMain)
        }

        jvm {
            mainRun {
                mainClass = "cn.enaium.sdl.example.mixer.Main_jvmKt"
            }
        }

        commonMain {
            dependencies {
                implementation(project(":sdl-mixer-kmp"))
                // The example drives SDL audio init directly.
                implementation(libs.sdl.kmp)
            }
        }
    }
}

tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
