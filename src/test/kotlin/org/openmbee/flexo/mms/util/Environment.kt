package org.openmbee.flexo.mms.util

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*
import io.ktor.server.testing.*
import java.io.InputStreamReader

/**
 * Load test environment from application.conf.test resource
 */
fun testEnv(): ApplicationConfig {
    return Thread.currentThread().contextClassLoader.getResourceAsStream("application.conf.test")?.let { stream ->
        InputStreamReader(stream).use { reader ->
            HoconApplicationConfig(ConfigFactory.parseReader(reader).resolve())
        }
    } ?: throw IllegalStateException("application.conf.test not found")
}

/**
 * Wrapper around Ktor's testApplication that automatically loads the
 * application.conf.test configuration file. In Ktor 3, testApplication
 * no longer auto-loads modules from config files.
 */
fun testApplication(block: suspend ApplicationTestBuilder.() -> Unit) {
    val hoconConfig = HoconApplicationConfig(
        ConfigFactory.parseResources("application.conf.test").resolve()
    )
    io.ktor.server.testing.testApplication {
        environment {
            config = hoconConfig
        }
        block()
    }
}

