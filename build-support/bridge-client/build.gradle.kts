plugins { `java-library` }
sourceSets.main { java.srcDir(rootProject.file(".deps/moonlightbridge/java/moonlight-bridge-client/src/main/java")) }
dependencies { implementation("com.github.luben:zstd-jni:1.5.7-20") }
