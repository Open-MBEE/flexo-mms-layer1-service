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


/**
 * URL to submit SPARQL queries when user is performing read operation.
 */
val Application.quadStoreQueryUrl: String
    get() = environment.config.property("mms.quad-store.query-url").getString()

/**
 * URL to submit SPARQL updates when application is performing write operation.
 */
val Application.quadStoreUpdateUrl: String
    get() = environment.config.property("mms.quad-store.update-url").getString()

/**
 * URL to submit [Graph Store Protocol](https://www.w3.org/TR/sparql11-http-rdf-update/) requests.
 */
val Application.quadStoreGraphStoreProtocolUrl: String?
    get() = environment.config.propertyOrNull("mms.quad-store.graph-store-protocol-url")?.getString()?.ifEmpty { null }

/**
 * Optional URL to a compatible MMS-5 load service.
 */
val Application.loadServiceUrl: String?
    get() = environment.config.propertyOrNull("mms.load-service.url")?.getString()?.ifEmpty { null }

/**
 * If set to `true`, responds with 404 to users who are not authorized to view a resource even if it exists.
 */
val Application.glomarResponse: Boolean
    get() = "true" == environment.config.propertyOrNull("mms.application.glomar-response")?.getString()

/**
 * Sets a limit on the size of string literals to store directly in the commit history, in KiB.
 */
val Application.maximumLiteralSizeKib: Long?
    get() = environment.config.propertyOrNull("mms.application.maximum-literal-size-kib")?.getString()?.toLongOrNull()

/**
 * Applies gzip compression to literals stored in the commit history when they exceed the given size, in KiB.
 */
val Application.gzipLiteralsLargerThanKib: Long?
    get() = environment.config.propertyOrNull("mms.application.gzip-literals-larger-than-kib")?.getString()?.toLongOrNull()

/**
 * Timeout per request sent to the triplestore, in seconds.
 */
val Application.requestTimeout: Long?
    get() = environment.config.propertyOrNull("mms.application.request-timeout")?.getString()?.toLongOrNull()
        ?.let { it * 1000 }

class AuthorizationRequiredException(message: String): Exception(message) {}
