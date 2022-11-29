package org.openmbee.mms5

import io.ktor.server.application.*
import org.openmbee.mms5.plugins.configureAuthentication
import org.openmbee.mms5.plugins.configureHTTP
import org.openmbee.mms5.plugins.configureRouting

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module(testing: Boolean=false) {
    configureAuthentication(environment)
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

val Application.glomarResponse: Boolean
    get() = "true" == environment.config.propertyOrNull("mms.application.glomar-response")?.getString()

val Application.maximumLiteralSizeKib: Long?
    get() = environment.config.propertyOrNull("mms.application.maximum-literal-size-kib")?.getString()?.toLongOrNull()

val Application.gzipLiteralsLargerThanKib: Long?
    get() = environment.config.propertyOrNull("mms.application.gzip-literals-larger-than")?.getString()?.toLongOrNull()

class AuthorizationRequiredException(message: String): Exception(message) {}
