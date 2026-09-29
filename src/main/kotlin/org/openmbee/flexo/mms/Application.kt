package org.openmbee.flexo.mms

import io.ktor.server.application.*
import org.openmbee.flexo.mms.server.configureAuthentication
import org.openmbee.flexo.mms.server.configureHTTP
import org.openmbee.flexo.mms.server.configureRouting

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused")
fun Application.module() {
    configureAuthentication()
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
 * URL to submit SPARQL queries when application is performing write operation. Useful for deployments that have
 * separate reader and writer instances, for which there may be some replica lag that would otherwise cause issues
 * during MMS-5's calls to verify a write operation succeeded.
 */
val Application.quadStoreMasterQueryUrl: String
    get() = environment.config.propertyOrNull("mms.quad-store.master-query-url")?.getString()
        ?.ifEmpty { this.quadStoreQueryUrl }
        ?: this.quadStoreQueryUrl

/**
 * URL to submit [Graph Store Protocol](https://www.w3.org/TR/sparql11-http-rdf-update/) requests.
 */
val Application.quadStoreGraphStoreProtocolUrl: String?
    get() = environment.config.propertyOrNull("mms.quad-store.graph-store-protocol-url")?.getString()?.ifEmpty { null }

/**
 * Comma-separated list of RDF content types the GSP backend accepts.
 */
val Application.quadStoreGraphStoreProtocolAccepts: String
    get() = environment.config.propertyOrNull("mms.quad-store.graph-store-protocol-accepts")?.getString()?.ifEmpty { "*/*" } ?: "*/*"

/**
 * Optional URL to a compatible MMS-5 store service.
 */
val Application.storeServiceUrl: String?
    get() = environment.config.propertyOrNull("mms.store-service.url")?.getString()?.ifEmpty { null }

/**
 * Comma-separated list of RDF content types the store service (and subsequently the SPARQL UPDATE LOAD) accepts
 */
val Application.storeServiceAccepts: String
    get() = environment.config.propertyOrNull("mms.store-service.accepts")?.getString()?.ifEmpty { "*/*" } ?: "*/*"

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
 * Maximum number of triples in one `INSERT DATA` / `DELETE DATA` block (or rows in a splittable VALUES table) of a
 * user update sent to the triplestore. Larger blocks are split. Unset: user updates are sent as written.
 */
val Application.maxTriplesPerUpdateBlock: Int?
    get() = environment.config.propertyOrNull("mms.application.max-triples-per-update-block")?.getString()?.toIntOrNull()
        ?.takeIf { it > 0 }

/**
 * Maximum size of one SPARQL update request sent to the triplestore for a user update, in KiB. Larger updates are
 * applied in several requests to a scratch copy of the target graph, which then replaces it. Unset: no limit.
 */
val Application.maxUpdateRequestKib: Long?
    get() = environment.config.propertyOrNull("mms.application.max-update-request-kib")?.getString()?.toLongOrNull()
        ?.takeIf { it > 0 }

/**
 * Timeout per request sent to the triplestore, in seconds.
 */
val Application.requestTimeout: Long?
    get() = environment.config.propertyOrNull("mms.application.request-timeout")?.getString()?.toLongOrNull()
        ?.let { it * 1000 }

/**
 * Commit finalization mode. "auto" (default) probes the quad-store once for transactional
 * multi-operation update support and sends the snapshot and branch-pointer updates as a single
 * atomic request when supported; "always" forces the single-request path; "never" always uses
 * two sequential requests (snapshot first, then branch pointer).
 */
val Application.atomicCommitMode: String
    get() = environment.config.propertyOrNull("mms.application.atomic-commit")?.getString()?.ifEmpty { null }
        ?: "auto"

/**
 * Age in seconds after which a ref-modifying transaction is considered abandoned (e.g. its
 * request crashed before releasing the mutex) and may be deleted by a new request acquiring the
 * same mutex. 0 disables stealing. Defaults to the triplestore request timeout plus a margin so
 * a legitimately long-running request is never stolen from.
 */
val Application.mutexTtlSeconds: Long
    get() = environment.config.propertyOrNull("mms.application.mutex-ttl-seconds")?.getString()?.toLongOrNull()
        ?: (((requestTimeout ?: (30L * 60 * 1000)) / 1000) + 300)

class AuthorizationRequiredException(message: String): Exception(message) {}
