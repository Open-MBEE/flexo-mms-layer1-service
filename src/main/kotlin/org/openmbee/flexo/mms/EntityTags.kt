package org.openmbee.flexo.mms

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import org.apache.jena.rdf.model.ResourceFactory


private val ETAG_VALUE = """(W/)?"([\w_-]+)"""".toRegex()

private val ETAG_PROPERTY = ResourceFactory.createProperty("urn:mms:etag")

data class EtagQualifier(
    val etags: HashSet<String>,
    val isStar: Boolean=false,
)

private val STAR_ETAG_QUALIFIER = EtagQualifier(hashSetOf(), true)

fun ApplicationCall.parseEtagQualifierHeader(headerKey: String): EtagQualifier? {
    val value = request.header(headerKey)?.trim()
    return if(value != null) {
        if(value == "*") STAR_ETAG_QUALIFIER
        else EtagQualifier(
            value.split(COMMA_SEPARATED).map {
                ETAG_VALUE.matchEntire(it)?.groupValues?.get(2)
                    ?: throw InvalidHeaderValue("$headerKey: \"$it\"")
            }.toHashSet())
    } else null
}



fun AnyLayer1Context.injectPreconditions(): String {
    return """
        ${if(ifMatch?.isStar == false) """
            values ?__mms_etag {
                ${ifMatch.etags.joinToString(" ") { escapeLiteral(it) }}
            }
        """ else ""}
        
        ${if(ifNoneMatch != null) """
            filter(?__mms_etag != ?__mms_etagNot)
            values ?__mms_etagNot {
                ${ifNoneMatch.etags.joinToString(" ") { escapeLiteral(it) }}
            }
        """ else ""}
    """
}

fun AnyLayer1Context.assertPreconditions(builder: ConditionsBuilder, inject: ((String)->String)?=null) {
    if((ifMatch != null && !ifMatch.isStar) || ifNoneMatch != null) {
        if(ifNoneMatch?.isStar == true) {
            throw BadRequestException("Cannot provide `If-None-Match: *` precondition to target action")
        }

        if(inject != null) {
            builder.require("userPreconditions") {
                handler = { "User preconditions failed" to HttpStatusCode.PreconditionFailed }

                inject(injectPreconditions())
            }
        }
    }
}


