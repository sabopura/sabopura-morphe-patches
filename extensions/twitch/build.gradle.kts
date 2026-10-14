extension {
    name = "extensions/twitch.mpe"
}

android {
    namespace = "io.github.sabopura.morphe.extension.twitch"
}

// Amazon IVS classes exist inside Twitch only. Compile against copies and never bundle them.
val compileIvsStubs by tasks.registering(JavaCompile::class) {
    source(fileTree("ivs-stubs") { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("ivs-stubs/classes"))
    options.release.set(8)
}
val ivsStubsJar by tasks.registering(Jar::class) {
    dependsOn(compileIvsStubs)
    from(compileIvsStubs.flatMap { it.destinationDirectory })
    archiveFileName.set("ivs-stubs-compile-only.jar")
    destinationDirectory.set(layout.buildDirectory.dir("ivs-stubs"))
}

dependencies {
    implementation("com.caverock:androidsvg-aar:1.4@aar")
    compileOnly(files(ivsStubsJar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
