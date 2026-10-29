plugins { java; id("com.gradleup.shadow") }
dependencies {
    implementation(project(":core"))
    // JDK HttpClient sender instead of OkHttp: no Kotlin runtime inside the Velocity plugin.
    implementation("ru.moonlightproject:moonlight-bridge-otel:${property("moonlightBridgeVersion")}") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation("io.opentelemetry:opentelemetry-sdk:1.66.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.66.0") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-sender-jdk:1.66.0")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("net.kyori:adventure-text-serializer-plain:4.24.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.24.0")
    testImplementation("net.kyori:adventure-text-minimessage:4.24.0")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    // ThirdVelocity API (provided by the proxy at runtime). Vendored while the repository is
    // private; switch to com.github.Th1rdPlanet.ThirdVelocity:thirdvelocity-api via JitPack later.
    compileOnly(files("libs/thirdvelocity-api-1.0.0.jar"))
    testImplementation(files("libs/thirdvelocity-api-1.0.0.jar"))
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
}
tasks.jar { archiveClassifier.set("plain") }
tasks.shadowJar { archiveClassifier.set(""); mergeServiceFiles(); relocate("com.google.gson", "ru.moonlightproject.dev.selene.internal.gson"); relocate("org.yaml.snakeyaml", "ru.moonlightproject.dev.selene.internal.yaml"); relocate("io.opentelemetry", "ru.moonlightproject.dev.selene.internal.otel") }
tasks.build { dependsOn(tasks.shadowJar) }
