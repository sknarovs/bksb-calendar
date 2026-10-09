plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.14"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "lv.sknarovs.bikernieki.Main"
}

tasks.test {
    useJUnitPlatform()
}

graalvmNative {
    toolchainDetection = false
    binaries {
        all {
            resources.autodetect()
            // jsoup resolves links through java.net.URL; native images need the https handler.
            buildArgs.add("--enable-url-protocols=https")
        }
        named("main") {
            imageName = "bikernieki-calendar"
            // GraalVM targets ARMv8.1 by default; Raspberry Pi 3/4 cores are ARMv8.0.
            buildArgs.add("-march=compatibility")
        }
    }
}
