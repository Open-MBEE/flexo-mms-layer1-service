package org.openmbee.mms5

import io.ktor.application.*
import org.openmbee.mms5.plugins.configureHTTP
import org.openmbee.mms5.plugins.configureRouting

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module(testing: Boolean=false) {
    configureHTTP()
    configureRouting()
}


class AuthorizationRequiredException(message: String): Exception(message) {}
