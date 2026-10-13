plugins { `java-library` }
sourceSets.main { java.srcDir(rootProject.file(".deps/moonlightbridge/java/moonlight-bridge-java/src/main/java")) }
dependencies { api(project(":bridge-client")) }
