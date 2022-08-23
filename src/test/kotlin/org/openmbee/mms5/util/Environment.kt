package org.openmbee.mms5.util

import com.typesafe.config.ConfigFactory
import io.kotest.core.test.TestScope
import io.kotest.extensions.system.withSystemProperties
import io.ktor.server.config.*
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


fun <R> withTest(testName: String, test: TestScope?, engine: TestApplicationEngine.(String) -> R) : R {
    return withSystemProperties(mapOf(
        "MMS5_QUERY_URL" to backend.getQueryUrl(),
        "MMS5_UPDATE_URL" to backend.getQueryUrl(),
        "MMS5_GRAPH_STORE_PROTOCOL_URL" to backend.getGspdUrl(),
        "MMS5_TEST_NO_AUTH" to if(test?.testCase?.config?.tags?.contains(NoAuth) == true) "1" else "",
    )) {
        withApplication(testEnv()) {
            engine(testName)
        }
    }
}

fun <R> withTest(test: TestApplicationEngine.(String) -> R) : R {
    return withTest("(unnamed)", null, test)
}


fun <R> TestScope.withTest(test: TestApplicationEngine.(String) -> R) : R {
    return withTest(this.testCase.name.testName, this, test)
}
