package org.openmbee.flexo.mms.util

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*
import io.ktor.server.testing.*

private val cachedConfig: HoconApplicationConfig by lazy {
    HoconApplicationConfig(ConfigFactory.parseResources("application.conf.test").resolve())
}

/**
 * Load test environment from application.conf.test resource
 */
fun testEnv(): ApplicationConfig = cachedConfig

/**
 * Wrapper around Ktor's testApplication that automatically loads the
 * application.conf.test configuration file. In Ktor 3, testApplication
 * no longer auto-loads modules from config files.
 */
fun testApplication(block: suspend ApplicationTestBuilder.() -> Unit) {
    io.ktor.server.testing.testApplication {
        environment {
            config = cachedConfig
        }
        block()
    }
}


/**
 * Same as [testApplication], with the given `mms.application.*`-style keys overriding application.conf.test.
 */
fun testApplicationWith(overrides: Map<String, Any>, block: suspend ApplicationTestBuilder.() -> Unit) {
    val config = HoconApplicationConfig(
        ConfigFactory.parseMap(overrides)
            .withFallback(ConfigFactory.parseResources("application.conf.test"))
            .resolve()
    )
    io.ktor.server.testing.testApplication {
        environment {
            this.config = config
        }
        block()
    }
}
