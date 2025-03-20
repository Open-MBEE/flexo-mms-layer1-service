package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_ORG
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse
import org.openmbee.flexo.mms.server.LdpReadResponse

// reusable basic graph pattern for matching org(s)
private val SPARQL_BGP_ORG: (Boolean, Boolean) -> String = { allOrgs, allData -> """
    graph m-graph:Cluster {
        ${"optional {" iff allOrgs}${"""
            ?$SPARQL_VAR_NAME_ORG a mms:Org ;
                mms:etag ?__mms_etag ;
                ${"?org_p ?org_o ;" iff allData}
                .
        """.reindent(if(allOrgs) 3 else 2)}
        ${"}" iff allOrgs}
        
        ${"""
            optional {
                ?thing mms:org ?$SPARQL_VAR_NAME_ORG ;
                    ?thing_p ?thing_o ;
                    .
            }
        """.reindent(2) iff allData}
    }
    
    ${permittedActionSparqlBgp(Permission.READ_ORG, Scope.CLUSTER,
        if(allOrgs) "^mo:?$".toRegex() else null,
        if(allOrgs) "" else null)}
""" }

// construct graph of all relevant org metadata
private val SPARQL_CONSTRUCT_ORG: (Boolean, Boolean) -> String = { allOrgs, allData ->  """
    construct {
        ?$SPARQL_VAR_NAME_ORG ?org_p ?org_o ;
            mms:etag ?__mms_etag ;
            .
        
        ?thing ?thing_p ?thing_o .
        
        ${generateReadContextBgp(Permission.READ_ORG).reindent(2)}
    } where {
        ${SPARQL_BGP_ORG(allOrgs, allData).reindent(2)}
    }
""" }


/**
 * Fetches org(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpReadResponse>.fetchOrgs(allOrgs: Boolean=false, allData: Boolean=false): String {
    // cache whether this request is asking for all orgs
    val orgIri = if(allOrgs) null else prefixes["mo"]!!

    // fetch all org details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_ORG(allOrgs, allData)) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by orgId
        orgIri?.let {
            iri(
                SPARQL_VAR_NAME_ORG to it,
            )
        }
    }

    // parse the response
    parseConstructResponse(constructResponseText) {
        // hash all the org etags
        if(allOrgs) {
            handleEtagAndPreconditions(model, MMS.Org)
        }
        // just the individual org
        else {
            handleEtagAndPreconditions(model, orgIri)
        }
    }

    return constructResponseText
}

/**
 * Fetches org(s) metadata, ignoring unnecessary data to satisfy a HEAD request
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headOrgs(allOrgs: Boolean=false) {
    fetchOrgs(allOrgs, false)

    // respond
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Fetches org(s) metadata and all relevant data to satisfy a GET request
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getOrgs(allOrgs: Boolean=false) {
    val constructResponseText = fetchOrgs(allOrgs, true)

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}
