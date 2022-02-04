package org.openmbee

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*

abstract class HttpException(msg: String, private val statusCode: HttpStatusCode): Exception(msg) {
    open suspend fun handle(call: ApplicationCall, text: String?=this.stackTraceToString()) {
        call.respondText(text ?: "Unspecified error", status=statusCode)
    }
}


open class Http304Exception(msg: String): HttpException(msg, HttpStatusCode.NotModified) {
    override suspend fun handle(call: ApplicationCall, text: String?) {
        super.handle(call, "")
    }
}

// open class Http400Exception(msg: String): HttpException(msg, HttpStatusCode.BadRequest)

open class Http412Exception(msg: String): HttpException(msg, HttpStatusCode.PreconditionFailed)


class NotModifiedException(msg: String?=null): Http304Exception(msg?: "")

class InvalidHeaderValue(description: String): BadRequestException("Request contains an invalid header value for $description")

class PreconditionFailedException(type: String): Http412Exception("$type precondition failed")



