plugins { `java-library` }
dependencies {
    api(project(":bridge-java"))
    api("com.google.code.gson:gson:2.13.2")
}

tasks.register<JavaExec>("backendSmoke") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.dev.selene.core.BackendSmokeMain"
    args(providers.gradleProperty("seleneSmokeEndpoint").getOrElse("tcp://127.0.0.1:25581"))
}
