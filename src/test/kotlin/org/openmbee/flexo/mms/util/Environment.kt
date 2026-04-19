package org.openmbee.flexo.mms.util

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*
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

