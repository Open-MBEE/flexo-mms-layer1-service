import java.net.URI

plugins {
    application
    kotlin("jvm") version "1.7.20"
    kotlin("plugin.serialization") version "1.9.20"
    jacoco
}

group = "org.openmbee.mms5"
version = "0.1.0-ALPHA"
application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val testFuseki: Configuration by configurations.creating

dependencies {
    implementation(kotlin("stdlib"))

    val kotestVersion = "5.8.0"
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-json-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")

    val commonsCliVersion = "1.6.0"
    implementation("commons-cli:commons-cli:$commonsCliVersion")

    val kotlinxJsonVersion = "1.6.0"
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxJsonVersion")

    val jenaVersion = "4.10.0"
    implementation("org.apache.jena:jena-arq:${jenaVersion}")
    testImplementation("org.apache.jena:jena-rdfconnection:${jenaVersion}");
    testFuseki("org.apache.jena:jena-fuseki-server:$jenaVersion")

    val ktorVersion = "2.3.4"
    implementation("io.ktor:ktor-client-core:${ktorVersion}")
    implementation("io.ktor:ktor-client-cio:${ktorVersion}")
    implementation("io.ktor:ktor-client-content-negotiation:${ktorVersion}")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-host-common:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-locations:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")
    // implementation("io.ktor:ktor-server-:$ktorVersion")
    testImplementation("io.ktor:ktor-server-tests:$ktorVersion")

    val logbackVersion = "1.4.11"
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    val systemLambdaVersion = "1.2.1"
    testImplementation("com.github.stefanbirkner:system-lambda:$systemLambdaVersion")

    val junitVersion = "5.10.1"
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")

    val migzVersion = "2.0.beta-1"
    implementation("com.linkedin.migz:migz:$migzVersion")

    // if/when needed, uncomment the block below to install dependencies from github repos
    // repositories {
    //     maven {
    //         url = URI("https://jitpack.io")
    //     }
    // }

    // implementation("com.github.$USER:$REPO:master-SNAPSHOT")
}

tasks {
    test {
        useJUnitPlatform()
        dependsOn("copy-test-fuseki-server")
        this.testLogging {
            this.showStandardStreams = true
        }
        environment("MMS5_ROOT_CONTEXT", System.getenv("MMS5_ROOT_CONTEXT"))
        environment("MMS5_QUERY_URL", System.getenv("MMS5_QUERY_URL"))
        environment("MMS5_UPDATE_URL", System.getenv("MMS5_UPDATE_URL"))
        environment("MMS5_GRAPH_STORE_PROTOCOL_URL", System.getenv("MMS5_GRAPH_STORE_PROTOCOL_URL"))
        if (System.getenv("MMS5_STORE_SERVICE_URL") != null)
            environment("MMS5_STORE_SERVICE_URL", System.getenv("MMS5_STORE_SERVICE_URL"))
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
