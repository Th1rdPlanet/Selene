plugins { java; id("com.gradleup.shadow") }
dependencies {
    implementation(project(":core"))
    implementation("net.kyori:adventure-text-serializer-plain:4.24.0")
    implementation("com.google.zxing:core:3.5.4")
    compileOnly("net.kyori:adventure-text-minimessage:4.24.0")
    testImplementation("net.kyori:adventure-text-minimessage:4.24.0")
    compileOnly("com.github.retrooper:packetevents-spigot:2.9.5")
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    // ExoLeaf API (provided by the ExoLeaf server at runtime), vendored until published.
    compileOnly(files("libs/exoleaf-api-1.0.0.jar"))
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}
tasks.jar { archiveClassifier.set("plain") }
tasks.shadowJar { archiveClassifier.set(""); mergeServiceFiles(); relocate("com.google.gson", "ru.moonlightproject.dev.selene.internal.gson"); relocate("com.google.zxing", "ru.moonlightproject.dev.selene.internal.zxing") }
tasks.build { dependsOn(tasks.shadowJar) }
