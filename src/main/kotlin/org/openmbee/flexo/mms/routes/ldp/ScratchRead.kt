package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_SCRATCH
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse


private const val SPARQL_VAR_NAME_CONTEXT = "_context"

// reusable basic graph pattern for matching scratch(es)
private val SPARQL_BGP_SCRATCH = """
    graph mor-graph:Metadata {
        ?$SPARQL_VAR_NAME_SCRATCH a mms:Scratch ;
            mms:id ?__mmd_id ;
            ?scratch_p ?scratch_o .
    }
    
    ${permittedActionSparqlBgp(Permission.READ_SCRATCH, Scope.REPO)}
"""

// select ID(s) of existing scratch(es)
private val SPARQL_SELECT_SCRATCH_IDS = """
    select distinct ?__mms_id {
        $SPARQL_BGP_SCRATCH
    } order by asc(?__mms_id)
"""

// construct graph of all relevant scratch metadata
private val SPARQL_CONSTRUCT_SCRATCH = """
    construct {
        ?$SPARQL_VAR_NAME_SCRATCH ?scratch_p ?scratch_o ;
            mms:id ?__mmd_id ;
            .
        
        ?$SPARQL_VAR_NAME_CONTEXT a mms:Context ;
            mms:permit mms-object:Permission.ReadScratch ;
            mms:policy ?policy ;
            .
        
        #?__mms_policy ?__mms_policy_p ?__mms_policy_o .
        
        ?scratchPolicy ?scratchPolicy_p ?scratchPolicy_o .
    } where {
        $SPARQL_BGP_SCRATCH
        
        graph m-graph:AccessControl.Policies {
            #?__mms_policy ?__mms_policy_p ?__mms_policy_o .

            optional {
                ?scratchPolicy a mms:Policy ;
                    mms:scope ?$SPARQL_VAR_NAME_SCRATCH ;
                    ?scratchPolicy_p ?scratchPolicy_o .
            }
        }
    }
"""


/**
 * Tests access to the scratch(es)
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headScratches(allScratches: Boolean=false) {
    val scratchIri = if(allScratches) null else prefixes["mors"]!!

    // fetch all scratches
    val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_SCRATCH_IDS) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by scratchId
        scratchIri?.let {
            iri(
                SPARQL_VAR_NAME_SCRATCH to it,
            )
        }

        // bind a context IRI
        iri(
            SPARQL_VAR_NAME_CONTEXT to "${MMS_URNS.SUBJECT.context}:$transactionId",
        )
    }

//    TODO: where are the access-control checks? Not convinced on this - repo read doesn't have this, add to SPARQL_SELECT_SCRATCH_IDS above?

//    // parse the result bindings
//    val bindings = parseSparqlResultsJsonSelect(selectResponseText)
//
//    // hash all the scratch etags
//    handleEtagAndPreconditions(bindings)

    // respond
    call.respond(HttpStatusCode.NoContent)
}


/**
 * Fetches scratch(es) metadata
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getScratches(allScratches: Boolean=false) {
    // cache whether this request is asking for all scratches
    val scratchIri = if(allScratches) null else prefixes["mors"]!!

    // fetch all scratch details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_SCRATCH) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by scratchId
        scratchIri?.let {
            iri(
                SPARQL_VAR_NAME_SCRATCH to it,
            )
        }

        // bind a context IRI
        iri(
            SPARQL_VAR_NAME_CONTEXT to "${MMS_URNS.SUBJECT.context}:$transactionId",
        )
    }

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}

