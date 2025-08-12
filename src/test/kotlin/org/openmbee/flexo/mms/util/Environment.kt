package org.openmbee.flexo.mms.util

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

