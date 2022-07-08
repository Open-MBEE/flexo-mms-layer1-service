package org.openmbee.mms5.util

import com.typesafe.config.ConfigFactory
import io.kotest.core.test.TestScope
import io.kotest.extensions.system.withEnvironment
import io.kotest.extensions.system.withSystemProperties
import io.ktor.config.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import java.io.InputStreamReader

/**
 * Load test environment from application.conf.example resource
 */
fun testEnv(): ApplicationEngineEnvironment {
    return createTestEnvironment {
        javaClass.classLoader.getResourceAsStream("application.conf.test")?.let { it ->
            InputStreamReader(it).use { iit ->
                config = HoconApplicationConfig(ConfigFactory.parseReader(iit).resolve())
            }
        }
    }
}


fun <R> withTest(testName: String, test: TestApplicationEngine.(String) -> R) : R {
    return withSystemProperties(mapOf(
        "MMS5_QUERY_URL" to backend.getQueryUrl(),
        "MMS5_UPDATE_URL" to backend.getQueryUrl(),
        "MMS5_GRAPH_STORE_PROTOCOL_URL" to backend.getGspdUrl(),
    )) {
        withApplication(testEnv()) {
            test(testName)
        }
    }
}

fun <R> withTest(test: TestApplicationEngine.(String) -> R) : R {
    return withTest("(unnamed)", test)
}

fun <R> TestScope.withTest(test: TestApplicationEngine.(String) -> R) : R {
    return withTest(this.testCase.name.testName, test)
}
