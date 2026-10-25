plugins { base; id("com.gradleup.shadow") version "9.6.1" apply false }
allprojects {
    group = "ru.moonlightproject.dev.selene"
    version = "0.1.0-dev"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
    }
}
subprojects {
    apply(plugin = "java-library")
    tasks.withType<JavaCompile>().configureEach { options.release.set(21); options.encoding = "UTF-8" }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Jar>().configureEach {
        manifest.attributes("Implementation-Vendor" to "~VicTim~", "Implementation-URL" to "https://dev.moonlightproject.ru", "MoonLightBridge-Revision" to "44eff0f9de2a8e5ceb3663d3935b7b9e327b143b")
    }
}
