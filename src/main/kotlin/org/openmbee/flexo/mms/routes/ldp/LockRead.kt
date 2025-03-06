package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_LOCK
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse
import org.openmbee.flexo.mms.server.LdpReadResponse


// reusable basic graph pattern for matching lock(s)
private val SPARQL_BGP_LOCK: (Boolean, Boolean) -> String = { allLocks, allData -> """
    graph mor-graph:Metadata {
        ${"optional {" iff allLocks}${"""
            ?$SPARQL_VAR_NAME_LOCK a mms:Lock ;
                mms:etag ?__mms_etag ;
                ${"?lock_p ?lock_o ;" iff allData}
                .
        """.reindent(if(allLocks) 3 else 2)}
        ${"}" iff allLocks}
        
        ${"""
            optional {
                ?thing mms:lock ?$SPARQL_VAR_NAME_LOCK ;
                    ?thing_p ?thing_o ;
                    .
            }
        """.reindent(2) iff allData}
    }
    
    ${permittedActionSparqlBgp(Permission.READ_LOCK, Scope.LOCK,
        if(allLocks) "^morl:?$".toRegex() else null,
        if(allLocks) "" else null)}
""" }

// construct graph of all relevant lock metadata
private val SPARQL_CONSTRUCT_LOCK: (Boolean, Boolean) -> String = { allLocks, allData ->  """
    construct {
        ?$SPARQL_VAR_NAME_LOCK ?lock_p ?lock_o ;
            mms:etag ?__mms_etag ;
            .
        
        ?thing ?thing_p ?thing_o .

        ?lockPolicy ?lockPolicy_p ?lockPolicy_o .
        
        ${generateReadContextBgp(Permission.READ_LOCK).reindent(2)}
    } where {
        ${SPARQL_BGP_LOCK(allLocks, allData)}
        
        optional {
            graph m-graph:AccessControl.Policies {
                ?lockPolicy a mms:Policy ;
                    mms:scope ?$SPARQL_VAR_NAME_LOCK ;
                    ?lockPolicy_p ?lockPolicy_o .
            }
        }
    }
""" }


/**
 * Fetches lock(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpReadResponse>.fetchLocks(allLocks: Boolean=false, allData: Boolean=false): String {
    // cache whether this request is asking for all locks
    val lockIri = if (allLocks) null else prefixes["morl"]!!

    // fetch all lock details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_LOCK(allLocks, allData)) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by groupId
        lockIri?.let {
            iri(
                SPARQL_VAR_NAME_LOCK to it,
            )
        }
    }

    // parse the response
    parseConstructResponse(constructResponseText) {
        // hash all the repo etags
        if (allLocks) {
            handleEtagAndPreconditions(model, MMS.Lock)
        }
        // just the individual repo
        else {
            handleEtagAndPreconditions(model, lockIri)
        }
    }

    return constructResponseText
}

/**
 * Fetches lock(s) metadata, ignoring unnecessary data to satisfy a HEAD request
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headLocks(allLocks: Boolean=false) {
    fetchLocks(allLocks, false)

    // respond
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Fetches lock(s) metadata and all relevant data to satisfy a GET request
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getLocks(allLocks: Boolean=false) {
    val constructResponseText = fetchLocks(allLocks, true)

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}
