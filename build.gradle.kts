import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    application
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    jacoco
    id("org.sonarqube") version "6.2.0.5505"
}

group = "org.openmbee.flexo.mms"
version = "0.2.0"

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val testFuseki: Configuration by configurations.creating

dependencies {
    implementation(kotlin("stdlib"))

    val kotestVersion = "5.9.1"
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-json-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")

    val commonsCliVersion = "1.9.0"
    implementation("commons-cli:commons-cli:$commonsCliVersion")

    val kotlinxJsonVersion = "1.8.1"
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxJsonVersion")

    val jenaVersion = "4.10.0"
    implementation("org.apache.jena:jena-arq:${jenaVersion}")
    testImplementation("org.apache.jena:jena-rdfconnection:${jenaVersion}");
    testFuseki("org.apache.jena:jena-fuseki-server:$jenaVersion")

    val ktorVersion = "2.3.4"
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
    implementation("io.ktor:ktor-server-locations:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    testImplementation("io.ktor:ktor-server-tests:$ktorVersion")
    testImplementation("io.kotest.extensions:kotest-assertions-ktor:2.0.0")

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
        environment("FLEXO_MMS_ROOT_CONTEXT", System.getenv("FLEXO_MMS_ROOT_CONTEXT"))
        environment("FLEXO_MMS_QUERY_URL", System.getenv("FLEXO_MMS_QUERY_URL"))
        environment("FLEXO_MMS_UPDATE_URL", System.getenv("FLEXO_MMS_UPDATE_URL"))
        environment("FLEXO_MMS_GRAPH_STORE_PROTOCOL_URL", System.getenv("FLEXO_MMS_GRAPH_STORE_PROTOCOL_URL"))
        if (System.getenv("FLEXO_MMS_STORE_SERVICE_URL") != null)
            environment("FLEXO_MMS_STORE_SERVICE_URL", System.getenv("FLEXO_MMS_STORE_SERVICE_URL"))
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
    val buildInfoFile = file("$buildDir/resources/main/build-info.properties")
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

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.add("-Xdebug")
}

kotlin {
    jvmToolchain(17)
}