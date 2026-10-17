group = "io.github.sabopura.morphe.patches"

patches {
    about {
        name = "Sabopura Morphe Patches"
        description = "Patches for use with Morphe: danmaku comments and ambient light for YouTube, chat, danmaku and playback enhancements for Twitch, auto-translate for Reddit, mission reward auto-claim for SmartNews, and ad, telemetry or popup cleanup and fixes for SmartNews, Pinterest, pixiv, Google News and Sankaku."
        source = "https://github.com/sabopura/sabopura-morphe-patches"
        author = "sabopura"
        contact = "na"
        website = "https://github.com/sabopura/sabopura-morphe-patches"
        license = "GPLv3"
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath: Configuration by configurations.creating

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
    testImplementation("junit:junit:4.13.2")
}

configurations.testImplementation { extendsFrom(configurations.compileOnly.get()) }

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
