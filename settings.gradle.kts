pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "Selene"
include("core", "paper", "velocity", "bridge-client", "bridge-java")
project(":bridge-client").projectDir = file("build-support/bridge-client")
project(":bridge-java").projectDir = file("build-support/bridge-java")
