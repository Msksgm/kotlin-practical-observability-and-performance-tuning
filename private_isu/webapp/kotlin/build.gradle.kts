
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.github.msksgm"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.freemarker)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.sessions)
    implementation(libs.logback.classic)

    implementation(libs.hikari.cp)
    implementation(libs.mysql.connector)
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)

    implementation(libs.xmemcached)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}
