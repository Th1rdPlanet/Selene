plugins { java; id("com.gradleup.shadow") }
dependencies {
    implementation(project(":core"))
    compileOnly("net.kyori:adventure-text-minimessage:4.24.0")
    testImplementation("net.kyori:adventure-text-minimessage:4.24.0")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
}
tasks.jar { archiveClassifier.set("plain") }
tasks.shadowJar { archiveClassifier.set(""); mergeServiceFiles(); relocate("com.google.gson", "ru.moonlightproject.dev.selene.internal.gson") }
tasks.build { dependsOn(tasks.shadowJar) }
