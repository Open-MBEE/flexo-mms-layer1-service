package org.openmbee.mms5

import io.ktor.application.*
import org.openmbee.mms5.plugins.configureAuthentication
import org.openmbee.mms5.plugins.configureHTTP
import org.openmbee.mms5.plugins.configureRouting

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module(testing: Boolean=false) {

    configureAuthentication()
    configureHTTP()
    configureRouting()
}


val Application.quadStoreQueryUrl: String
    get() = environment.config.property("mms.quad-store.query-url").getString()

val Application.quadStoreUpdateUrl: String
    get() = environment.config.property("mms.quad-store.update-url").getString()

val Application.quadStoreGraphStoreProtocolUrl: String?
    get() = environment.config.propertyOrNull("mms.quad-store.graph-store-protocol-url")?.getString()?.ifEmpty { null }

val Application.loadServiceUrl: String?
    get() = environment.config.propertyOrNull("mms.load-service.url")?.getString()?.ifEmpty { null }


class AuthorizationRequiredException(message: String): Exception(message) {}
