pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "Selene"
include("core", "paper", "velocity", "bridge-client", "bridge-java", "bridge-otel")
project(":bridge-client").projectDir = file("build-support/bridge-client")
project(":bridge-java").projectDir = file("build-support/bridge-java")
project(":bridge-otel").projectDir = file("build-support/bridge-otel")
