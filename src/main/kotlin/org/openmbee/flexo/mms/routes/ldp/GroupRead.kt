package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_GROUP
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse
import org.openmbee.flexo.mms.server.LdpReadResponse

private const val SPARQL_VAR_NAME_CONTEXT = "_context"

// reusable basic graph pattern for matching group(s)
private val SPARQL_BGP_GROUP: (Boolean, Boolean) -> String = { allGroups, allData -> """
    graph m-graph:AccessControl.Agents {
        ${"""
            optional {
                ?thing mms:group ?$SPARQL_VAR_NAME_GROUP ;
                    ?thing_p ?thing_o .
            }            
        """.reindent(2) iff allData}

        ${"optional {" iff allGroups}${"""
            ?$SPARQL_VAR_NAME_GROUP a mms:Group ;
                mms:etag ?__mms_etag ;
                ?group_p ?group_o .
        """.reindent(if(allGroups) 3 else 2)}
        ${"}" iff allGroups}
    }
    
    ${permittedActionSparqlBgp(Permission.READ_GROUP, Scope.CLUSTER)}
"""}

// construct graph of all relevant group metadata
private val SPARQL_CONSTRUCT_GROUP: (Boolean, Boolean) -> String = { allGroups, allData ->  """
    construct {
        ?$SPARQL_VAR_NAME_GROUP ?group_p ?group_o ;
            mms:etag ?__mms_etag ;
            .
        
        ?thing ?thing_p ?thing_o .
        
        ?groupPolicy ?groupPolicy_p ?groupPolicy_o .
        
        ${generateReadContextBgp(Permission.READ_GROUP).reindent(2)}
    } where {
        ${SPARQL_BGP_GROUP(allGroups, allData)}
        
        graph m-graph:AccessControl.Policies {
            optional {
                ?groupPolicy a mms:Policy ;
                    mms:scope ?$SPARQL_VAR_NAME_GROUP ;
                    ?groupPolicy_p ?groupPolicy_o .
            }
        }
    }
"""}


/**
 * Fetches group(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpReadResponse>.fetchGroups(allGroups: Boolean=false, allData: Boolean=false): String {
    // cache whether this request is asking for all groups
    val groupIri = if(allGroups) null else prefixes["mg"]!!

    // fetch all group details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_GROUP(allGroups, allData)) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by groupId
        groupIri?.let {
            iri(
                SPARQL_VAR_NAME_GROUP to it,
            )
        }
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

    return constructResponseText
}

/**
 * Fetches group(s) metadata, ignoring unnecessary data to satisfy a HEAD request
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headGroups(allGroups: Boolean=false) {
    fetchGroups(allGroups, false)

    // respond
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Fetches group(s) metadata and all relevant data to satisfy a GET request
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getGroups(allGroups: Boolean=false) {
    val constructResponseText = fetchGroups(allGroups, true)

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}
