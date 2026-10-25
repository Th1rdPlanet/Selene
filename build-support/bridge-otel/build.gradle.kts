plugins { `java-library` }
sourceSets.main { java.srcDir(rootProject.file(".deps/moonlightbridge/java/moonlight-bridge-otel/src/main/java")) }
dependencies {
    api(project(":bridge-client"))
    api("io.opentelemetry:opentelemetry-api:1.66.0")
    api("io.opentelemetry:opentelemetry-sdk:1.66.0")
    // JDK HttpClient sender instead of OkHttp: no Kotlin runtime inside the Velocity plugin.
    api("io.opentelemetry:opentelemetry-exporter-otlp:1.66.0") { exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp") }
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-sender-jdk:1.66.0")
}
