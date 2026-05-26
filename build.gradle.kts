import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    application
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.21"
    jacoco
    id("org.sonarqube") version "6.2.0.5505"
}

group = "org.openmbee.flexo.mms"
version = "0.3.1"

sonar {
    properties {
        property("sonar.projectKey", "Open-MBEE_flexo-mms-layer1-service")
        property("sonar.organization", "openmbee")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
    }
}
application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val testFuseki: Configuration by configurations.creating

dependencies {
    implementation(kotlin("stdlib"))

    val kotestVersion = "6.1.11"
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-json-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")

    val commonsCliVersion = "1.9.0"
    implementation("commons-cli:commons-cli:$commonsCliVersion")

    val kotlinxJsonVersion = "1.9.0"
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxJsonVersion")

    val jenaVersion = "6.0.0"
    implementation("org.apache.jena:jena-arq:${jenaVersion}")
    testImplementation("org.apache.jena:jena-rdfconnection:${jenaVersion}");
    testFuseki("org.apache.jena:jena-fuseki-server:$jenaVersion")

    val ktorVersion = "3.4.2"
    implementation("io.ktor:ktor-client-core:${ktorVersion}")
    implementation("io.ktor:ktor-client-content-negotiation:${ktorVersion}")
    implementation("io.ktor:ktor-client-cio:${ktorVersion}")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-server-host-common:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.kotest:kotest-assertions-ktor:$kotestVersion")

    val logbackVersion = "1.5.18"
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    val systemLambdaVersion = "1.2.1"
    testImplementation("com.github.stefanbirkner:system-lambda:$systemLambdaVersion")

    val junitVersion = "5.13.1"
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
        this.testLogging {
            this.showStandardStreams = true
        }
        environment("FLEXO_MMS_ROOT_CONTEXT", System.getenv("FLEXO_MMS_ROOT_CONTEXT") ?: "http://layer1-service")
        environment("FLEXO_MMS_QUERY_URL", System.getenv("FLEXO_MMS_QUERY_URL") ?: "http://localhost:3030/ds/sparql")
        environment("FLEXO_MMS_UPDATE_URL", System.getenv("FLEXO_MMS_UPDATE_URL") ?: "http://localhost:3030/ds/update")
        environment("FLEXO_MMS_GRAPH_STORE_PROTOCOL_URL", System.getenv("FLEXO_MMS_GRAPH_STORE_PROTOCOL_URL") ?: "http://localhost:3030/ds/data")
        if (System.getenv("FLEXO_MMS_STORE_SERVICE_URL") != null)
            environment("FLEXO_MMS_STORE_SERVICE_URL", System.getenv("FLEXO_MMS_STORE_SERVICE_URL") ?: "http://localhost:8081/store")
    }
}
tasks.test {
    finalizedBy(tasks.jacocoTestReport) // report is always generated after tests run
}
tasks.jacocoTestReport {
    dependsOn(tasks.test) // tests are required to run before generating the report
    reports {
        xml.required.set(true)
    }
}
tasks.register("generateBuildInfo") {
    val buildInfoFile = layout.buildDirectory.file("resources/main/build-info.properties").get().asFile
    outputs.file(buildInfoFile)
    doLast {
        buildInfoFile.writeText(
            """
            build.version=${project.version}
            """.trimIndent()
        )
    }
}

tasks.named("processResources") {
    finalizedBy("generateBuildInfo")
}

tasks.named("jar") {
    dependsOn("generateBuildInfo")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.add("-Xdebug")
}

kotlin {
    jvmToolchain(21)
}
