plugins { java; id("com.gradleup.shadow") }
dependencies {
    implementation(project(":core"))
    implementation(project(":bridge-otel"))
    implementation("org.yaml:snakeyaml:2.4")
    implementation("net.kyori:adventure-text-serializer-plain:4.24.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.24.0")
    testImplementation("net.kyori:adventure-text-minimessage:4.24.0")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
}
tasks.jar { archiveClassifier.set("plain") }
tasks.shadowJar { archiveClassifier.set(""); mergeServiceFiles(); relocate("com.google.gson", "ru.moonlightproject.dev.selene.internal.gson"); relocate("org.yaml.snakeyaml", "ru.moonlightproject.dev.selene.internal.yaml"); relocate("io.opentelemetry", "ru.moonlightproject.dev.selene.internal.otel") }
tasks.build { dependsOn(tasks.shadowJar) }
