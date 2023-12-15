package org.openmbee.flexo.mms

import io.ktor.http.*
import io.ktor.server.application.*
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

class IllegalIdException: Http400Exception("Illegal ID string. Must be at least 3 characters long. Letter symbols and special characters '.' '-' '_' allowed.")

class ForbiddenPrefixException(prefix: String): Http400Exception("Prefix not allowed here: $prefix")

class ForbiddenPrefixRemapException(prefix: String, iri: String): Http400Exception("Prefix \"$prefix\" not allowed to be set to anything other than <$iri>")

class InvalidQueryParameter(detail: String): Http400Exception("Request contains invalid query parameter(s): $detail")

class PreconditionsForbidden(detail: String): Http400Exception("Cannot use preconditions here: $detail")


open class Http403Exception(layer1: AnyLayer1Context, resource: String="(unspecified)"): HttpException("User ${layer1.userId} (${layer1.groups.joinToString(", ") { "<$it>" }}) is not authorized to perform specified action on resource: $resource", HttpStatusCode.Forbidden)


open class Http404Exception(resource: String): HttpException("The requested resource does not exist: $resource", HttpStatusCode.NotFound)


open class Http405Exception(msg: String): HttpException(msg, HttpStatusCode.MethodNotAllowed)

class MethodNotAllowedException(): Http405Exception("The requested method is not allow on this resource")


open class Http406Exception(msg: String): HttpException(msg, HttpStatusCode.NotAcceptable)

class NotAcceptableException(detail: String): Http406Exception("Not acceptable. $detail") {
    constructor(acceptValue: String, more: String): this("Unable to provide content matching $acceptValue. $more")
    constructor(accepts: ContentType?, more: String): this("$accepts", more)
    constructor(accepts: String?, provides: Collection<ContentType>): this(accepts ?: "", "Must be one of ${provides.joinToString(", ")}")
}



open class Http412Exception(msg: String): HttpException(msg, HttpStatusCode.PreconditionFailed)

class PreconditionFailedException(detail: String): Http412Exception("Precondition failed. $detail")


open class Http415Exception(msg: String): HttpException(msg, HttpStatusCode.UnsupportedMediaType)

class UnsupportedMediaType(detail: String): Http415Exception("Unsupported media type. $detail") {
    constructor(expected: Collection<ContentType>): this("Expected one of: ${expected.joinToString(", ")}")
}


class InvalidTriplesDocumentTypeException(detail: String): Http415Exception("The input document content-type is not recognized as an acceptable triples format: $detail")


open class Http500Excpetion(msg: String): HttpException(msg, HttpStatusCode.InternalServerError)

class ServerBugException(msg: String?=null): Http500Excpetion("Possible server implementation bug: ${msg?: "(no description)"}")

