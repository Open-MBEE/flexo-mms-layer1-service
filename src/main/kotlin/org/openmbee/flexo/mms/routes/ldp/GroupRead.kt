package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_GROUP
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse

private const val SPARQL_VAR_NAME_CONTEXT = "_context"

// reusable basic graph pattern for matching group(s)
private val SPARQL_BGP_GROUP = """
    graph m-graph:AccessControl.Agents {
        ?$SPARQL_VAR_NAME_GROUP a mms:Group ;
            mms:etag ?__mms_etag ;
            ?group_p ?group_o .
        
        optional {
            ?thing mms:group ?$SPARQL_VAR_NAME_GROUP ;
                ?thing_p ?thing_o .
        }
    }
    
    ${permittedActionSparqlBgp(Permission.READ_GROUP, Scope.CLUSTER)}
"""

// select ETag(s) of existing group(s)
private val SPARQL_SELECT_GROUP_ETAGS = """
    select distinct ?__mms_etag {
        $SPARQL_BGP_GROUP
    } order by asc(?__mms_etag)
"""

// construct graph of all relevant group metadata
private val SPARQL_CONSTRUCT_GROUP = """
    construct {
        ?$SPARQL_VAR_NAME_GROUP ?group_p ?group_o ;
            mms:etag ?__mms_etag .
        
        ?thing ?thing_p ?thing_o .
        
        ?$SPARQL_VAR_NAME_CONTEXT a mms:Context ;
            mms:permit mms-object:Permission.ReadGroup ;
            mms:policy ?policy ;
            .
        
        ?groupPolicy ?groupPolicy_p ?groupPolicy_o .
    } where {
        $SPARQL_BGP_GROUP
        
        graph m-graph:AccessControl.Policies {
            optional {
                ?groupPolicy a mms:Policy ;
                    mms:scope ?$SPARQL_VAR_NAME_GROUP ;
                    ?groupPolicy_p ?groupPolicy_o .
            }
        }
    }
"""


/**
 * Fetches the group(s) ETag
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headGroups(allGroups: Boolean=false) {
    val groupIri = if(allGroups) null else prefixes["mg"]!!

    // fetch all groups
     val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_GROUP_ETAGS) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by groupId
        groupIri?.let {
            iri(
                SPARQL_VAR_NAME_GROUP to it,
            )
        }

        // bind a context IRI
        iri(
            SPARQL_VAR_NAME_CONTEXT to "${MMS_URNS.SUBJECT.context}:$transactionId",
        )
    }

    // parse the result bindings
    val bindings = parseSparqlResultsJsonSelect(selectResponseText)

    // hash all the group etags
    handleEtagAndPreconditions(bindings)

    // respond
    call.respond(HttpStatusCode.NoContent)
}


/**
 * Fetches group(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getGroups(allGroups: Boolean=false) {
    // cache whether this request is asking for all groups
    val groupIri = if(allGroups) null else prefixes["mg"]!!

    // fetch all group details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_GROUP) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by groupId
        groupIri?.let {
            iri(
                SPARQL_VAR_NAME_GROUP to it,
            )
        }

        // bind a context IRI
        iri(
            SPARQL_VAR_NAME_CONTEXT to "${MMS_URNS.SUBJECT.context}:$transactionId",
        )
    }

    // parse the response
    parseConstructResponse(constructResponseText) {
        // hash all the group etags
        if(allGroups) {
            handleEtagAndPreconditions(model, MMS.Group)
        }
        // just the individual group
        else {
            handleEtagAndPreconditions(model, groupIri)
        }
    }

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}

