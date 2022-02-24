package org.openmbee.mms5

import io.ktor.application.*
import org.openmbee.mms5.plugins.configureAuthentication
import org.openmbee.mms5.plugins.configureHTTP
import org.openmbee.mms5.plugins.configureRouting
import org.openmbee.mms5.plugins.registerService

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module(testing: Boolean=false) {
    configureAuthentication()
    configureHTTP()
    configureRouting()

    if(environment.config.propertyOrNull("consul.enabled")?.getString() == "true") {
        registerService()
    }
}


class AuthorizationRequiredException(message: String): Exception(message) {}
