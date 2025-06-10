package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_SCRATCH
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse
import org.openmbee.flexo.mms.server.LdpReadResponse


// reusable basic graph pattern for matching scratch(es)
private val SPARQL_BGP_SCRATCH: (Boolean, Boolean) -> String = { allScratches, allData -> """
    graph mor-graph:Metadata {
        ${"optional {" iff allScratches}${"""
            ?$SPARQL_VAR_NAME_SCRATCH a mms:Scratch ;
                mms:etag ?__mms_etag ;
                ${"?scratch_p ?scratch_o ;" iff allData}
                .
        """.reindent(if(allScratches) 3 else 2)}
        ${"}" iff allScratches}
    }
    
    ${permittedActionSparqlBgp(Permission.READ_SCRATCH, Scope.SCRATCH,
        if(allScratches) "^mors:?$".toRegex() else null,
        if(allScratches) "" else null)}
""" }

// construct graph of all relevant scratch metadata
private val SPARQL_CONSTRUCT_SCRATCH: (Boolean, Boolean) -> String = { allScratches, allData -> """
    construct {
        ?$SPARQL_VAR_NAME_SCRATCH a mms:Scratch ;
            mms:etag ?__mms_etag ;
            ?scratch_p ?scratch_o ;
            .
        ${generateReadContextBgp(Permission.READ_SCRATCH).reindent(2)}
    } where {
        ${SPARQL_BGP_SCRATCH(allScratches, allData).reindent(2)}
    }
""" }

/**
 * Fetches scratches(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpReadResponse>.fetchScratches(allScratches: Boolean=false, allData: Boolean=false): String {
    // cache whether this request is asking for all scratches
    val scratchIri = if(allScratches) null else prefixes["mors"]!!

    // fetch all scratch details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_SCRATCH(allScratches, allData)) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by scratchId
        scratchIri?.let {
            iri(
                SPARQL_VAR_NAME_SCRATCH to it,
            )
        }
    }

    // parse the response
    parseConstructResponse(constructResponseText) {
        // hash all the scratch etags
        if(allScratches) {
            handleEtagAndPreconditions(model, MMS.Scratch)
        }
        // just the individual scratch
        else {
            handleEtagAndPreconditions(model, scratchIri)
        }
    }

    return constructResponseText
}
/**
 * Tests access to the scratch(es)
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headScratches(allScratches: Boolean=false) {
    fetchScratches(allScratches, false)

    // respond
    call.respond(HttpStatusCode.NoContent)
}


/**
 * Fetches scratch(es) metadata
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getScratches(allScratches: Boolean=false) {
    val constructResponseText = fetchScratches(allScratches, true)

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}

