package org.openmbee.flexo.mms

import io.ktor.client.engine.*
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*

open class HttpException(msg: String, private val statusCode: HttpStatusCode): Exception(msg) {
    open suspend fun handle(call: ApplicationCall, text: String?=this.stackTraceToString()) {
        call.respondText(text ?: "Unspecified error", status=statusCode)
    }
}

open class Http304Exception(msg: String): HttpException(msg, HttpStatusCode.NotModified) {
    override suspend fun handle(call: ApplicationCall, text: String?) {
        super.handle(call, "")
    }
}

class NotModifiedException(msg: String?=null): Http304Exception(msg?: "")



open class Http400Exception(msg: String): HttpException(msg, HttpStatusCode.BadRequest)

class InvalidHeaderValue(description: String): Http400Exception("Request contains an invalid header value for $description")

class VariablesNotAllowedInUpdateException(position: String="any"): Http400Exception("Variables are not allowed in $position position for an update operation on this resource")

class ConstraintViolationException(detail: String): Http400Exception("The input document violates the constraints for an MMS object: $detail")

class InvalidDocumentSemanticsException(detail: String): Http400Exception("The input document contains invalid semantics: $detail")

class InvalidTriplesDocumentTypeException(detail: String): Http400Exception("The input document content-type is not recognized as an acceptable triples format: $detail")

class IllegalIdException: Http400Exception("Illegal ID string. Must be at least 3 characters long. Letter symbols and special characters '.' '-' '_' allowed.")

class ForbiddenPrefixException(prefix: String): Http400Exception("Prefix not allowed here: $prefix")

class ForbiddenPrefixRemapException(prefix: String, iri: String): Http400Exception("Prefix \"$prefix\" not allowed to be set to anything other than <$iri>")

open class Http403Exception(mmsL1Context: MmsL1Context, resource: String="(unspecified)"): HttpException("User ${mmsL1Context.userId} (${mmsL1Context.groups.joinToString(", ") { "<$it>" }}) is not authorized to perform specified action on resource: $resource", HttpStatusCode.Forbidden)

open class Http404Exception(resource: String): HttpException("The requested resource does not exist: $resource", HttpStatusCode.NotFound)


open class Http412Exception(msg: String): HttpException(msg, HttpStatusCode.PreconditionFailed)

class PreconditionFailedException(type: String): Http412Exception("$type precondition failed")



open class Http500Excpetion(msg: String): HttpException(msg, HttpStatusCode.InternalServerError)

class ServerBugException(msg: String?=null): Http500Excpetion("Possible server implementation bug: ${msg?: "(no description)"}")

