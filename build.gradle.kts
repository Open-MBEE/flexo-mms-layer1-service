val jena_version: String by project
val klaxon_version: String by project
val kotlinx_json_version: String by project
val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project
val consul_version: String by project
val system_lambda_version: String by project
val junit_version: String by project

plugins {
    application
    kotlin("jvm") version "1.6.10"
    kotlin("plugin.serialization") version "1.6.10"
    jacoco
}

group = "org.openmbee.mms5"
version = "0.0.1"
application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
}

val testFuseki: Configuration by configurations.creating

dependencies {
    implementation(kotlin("stdlib"))

    implementation("commons-cli:commons-cli:1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_json_version")

    implementation("com.beust:klaxon:$klaxon_version")

    implementation("org.apache.jena:jena-arq:${jena_version}")

    implementation("io.ktor:ktor-client-core:${ktor_version}")
    implementation("io.ktor:ktor-client-cio:${ktor_version}")
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-host-common:$ktor_version")
    implementation("io.ktor:ktor-locations:$ktor_version")
    implementation("io.ktor:ktor-auth:$ktor_version")
    implementation("io.ktor:ktor-auth-jwt:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("com.orbitz.consul:consul-client:$consul_version")
    implementation("ch.qos.logback:logback-classic:$logback_version")

    testImplementation("io.ktor:ktor-server-tests:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation("com.github.stefanbirkner:system-lambda:$system_lambda_version")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junit_version")

    testFuseki("org.apache.jena:jena-fuseki-server:$jena_version")
}

tasks {
    test {
        useJUnitPlatform()
        dependsOn("copy-test-fuseki-server")
    }
    register<Copy>("copy-test-fuseki-server") {
        // Copy fuseki-server jar to known location (build/test-fuseki-server)
        from(testFuseki.resolvedConfiguration.files)
        destinationDir = project.buildDir.resolve("test-fuseki-server")
    }
}
tasks.test {
    finalizedBy(tasks.jacocoTestReport) // report is always generated after tests run
}
tasks.jacocoTestReport {
    dependsOn(tasks.test) // tests are required to run before generating the report
}