package org.openmbee.flexo.mms.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.HttpException

fun Application.configureHTTP() {
    install(ForwardedHeaders) // WARNING: for security, do not include this if not behind a reverse proxy
    install(XForwardedHeaders) // WARNING: for security, do not include this if not behind a reverse proxy
    install(DefaultHeaders) {
        header("X-Engine", "Ktor") // will send this header with each response
        header("Flexo-MMS-Layer-1", "Version=${BuildInfo.getProperty("build.version")}")
    }

    install(StatusPages) {
        exception<HttpException> { call, cause ->
            cause.handle(call)
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("internal error", cause)
            call.respondText(cause.stackTraceToString(), status=HttpStatusCode.InternalServerError)
        }
    }
    install(CallLogging)
    install(CORS) {
        allowCredentials = true

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.ETag)
        allowHeader(HttpHeaders.IfMatch)
        allowHeader(HttpHeaders.IfNoneMatch)
        allowHeader(HttpHeaders.SLUG)

        allowMethod(HttpMethod.Head)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)

        exposeHeader("Accept-Patch")
        exposeHeader("Accept-Put")
        exposeHeader(HttpHeaders.Allow)
        exposeHeader(HttpHeaders.ETag)
        exposeHeader(HttpHeaders.Date)
        exposeHeader(HttpHeaders.Location)
        exposeHeader(HttpHeaders.Link)
        exposeHeader("Flexo-Mms-Layer-1")

        anyHost() // @TODO: make configuration
    }

    install(ConditionalHeaders)
}
