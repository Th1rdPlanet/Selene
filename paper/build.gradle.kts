plugins { java; id("com.gradleup.shadow") }
dependencies {
    implementation(project(":core"))
    implementation("net.kyori:adventure-text-serializer-plain:4.24.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.24.0")
    testImplementation("net.kyori:adventure-text-minimessage:4.24.0")
    compileOnly("com.github.retrooper:packetevents-spigot:2.9.5")
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}
tasks.jar { archiveClassifier.set("plain") }
tasks.shadowJar { archiveClassifier.set(""); mergeServiceFiles(); relocate("com.google.gson", "ru.moonlightproject.dev.selene.internal.gson") }
tasks.build { dependsOn(tasks.shadowJar) }
