package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse

private val SPARQL_BGP_LOCK = """
    graph mor-graph:Metadata {
        ?_lock a mms:Lock ;
            mms:etag ?__mms_etag ;
            ?lock_p ?lock_o .
        
        optional {
            ?thing mms:lock ?_lock ;
                ?thing_p ?thing_o .
        }
    }
    
    ${permittedActionSparqlBgp(Permission.READ_LOCK, Scope.LOCK)}
"""

private val SPARQL_SELECT_LOCK = """
    select distinct ?__mms_etag {
        $SPARQL_BGP_LOCK
    } order by asc(?__mms_etag)
"""

private val SPARQL_CONSTRUCT_LOCK = """
    construct {
        ?_lock ?lock_p ?lock_o ;
            mms:etag ?__mms_etag .
        
        ?thing ?thing_p ?thing_o .
        
        ?_context a mms:Context ;
            mms:permit mms-object:Permission.ReadLock ;
            mms:appliedPolicy ?policy .
        
        ?lockPolicy ?lockPolicy_p ?lockPolicy_o .
    } where {
        $SPARQL_BGP_LOCK
        
        graph m-graph:AccessControl.Policies {
            optional {
                ?lockPolicy a mms:Policy ;
                    mms:scope ?_lock ;
                    ?lockPolicy_p ?lockPolicy_o .
            }
        }
    }
"""


/**
 * Fetches the locks(') ETag
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headLocks(allLocks: Boolean=false) {
    // use quicker select query to fetch etags
    val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_LOCK) {
        acceptReplicaLag = true

        prefixes(prefixes)

        // get by lockId
        if(!allLocks) {
            iri(
                "_lock" to prefixes["morl"]!!,
            )
        }

        iri(
            "_context" to "${MMS_URNS.SUBJECT.context}:$transactionId",
        )
    }

    // parse the results
    val bindings = parseSparqlResultsJsonSelect(selectResponseText)

    // hash all the lock etags
    handleEtagAndPreconditions(bindings)

    // respond
    call.respond(HttpStatusCode.NoContent)
}


/**
 * Fetches lock(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getLocks(allLocks: Boolean=false) {
    // fetch all lock details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_LOCK) {
        acceptReplicaLag = true

        prefixes(prefixes)

        // get by lockId
        if(!allLocks) {
            iri(
                "_lock" to prefixes["morl"]!!,
            )
        }

        iri(
            "_context" to "${MMS_URNS.SUBJECT.context}:$transactionId",
        )
    }

    // parse the response
    parseConstructResponse(constructResponseText) {
        // hash all the repo etags
        if(allLocks) {
            handleEtagAndPreconditions(model, MMS.Lock)
        }
        // just the individual repo
        else {
            handleEtagAndPreconditions(model, prefixes["morl"])
        }
    }

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}
