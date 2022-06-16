
val kotlinVersion = "1.6.10"

plugins {
    application
    kotlin("jvm") version "1.6.10"
    kotlin("plugin.serialization") version "1.7.0"
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

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")

    val commonsCliVersion = "1.4"
    implementation("commons-cli:commons-cli:$commonsCliVersion")

    val kotlinxJsonVersion = "1.3.2"
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxJsonVersion")

    val klaxonVersion = "5.5"
    implementation("com.beust:klaxon:$klaxonVersion")

    val jenaVersion = "4.2.0"
    implementation("org.apache.jena:jena-arq:${jenaVersion}")
    testFuseki("org.apache.jena:jena-fuseki-server:$jenaVersion")

    val ktorVersion = "1.6.7"
    implementation("io.ktor:ktor-client-core:${ktorVersion}")
    implementation("io.ktor:ktor-client-cio:${ktorVersion}")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-host-common:$ktorVersion")
    implementation("io.ktor:ktor-locations:$ktorVersion")
    implementation("io.ktor:ktor-auth:$ktorVersion")
    implementation("io.ktor:ktor-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    testImplementation("io.ktor:ktor-server-tests:$ktorVersion")

    val consulVersion = "1.5.3"
    implementation("com.orbitz.consul:consul-client:$consulVersion")

    val logbackVersion = "1.2.3"
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    val systemLambdaVersion = "1.2.1"
    testImplementation("com.github.stefanbirkner:system-lambda:$systemLambdaVersion")

    val junitVersion = "5.8.2"
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")

}

tasks {
    test {
        useJUnitPlatform()
        dependsOn("copy-test-fuseki-server")
        this.testLogging {
            this.showStandardStreams = true
        }
        environment("MMS5_ROOT_CONTEXT", System.getenv("MMS5_ROOT_CONTEXT"))
        environment("MMS5_LOAD_SERVICE_URL", System.getenv("MMS5_LOAD_SERVICE_URL"))
        environment("MMS5_QUAD_STORE_URL", System.getenv("MMS5_QUAD_STORE_URL"))
        environment("MMS5_QUERY_URL", System.getenv("MMS5_QUERY_URL"))
        environment("MMS5_UPDATE_URL", System.getenv("MMS5_UPDATE_URL"))
        environment("MMS5_GRAPH_STORE_PROTOCOL_URL", System.getenv("MMS5_GRAPH_STORE_PROTOCOL_URL"))
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