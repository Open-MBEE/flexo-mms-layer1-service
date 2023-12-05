package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.plugins.LdpDcLayer1Context
import org.openmbee.flexo.mms.plugins.LdpGetResponse
import org.openmbee.flexo.mms.plugins.LdpHeadResponse
import org.openmbee.flexo.mms.plugins.LdpReadResponse

private const val SPARQL_VAR_NAME_CONTEXT = "_context"

// reusable basic graph pattern for matching org(s)
private val SPARQL_BGP_ORG = """
    graph m-graph:Cluster {
        ?$SPARQL_VAR_NAME_ORG a mms:Org ;
            mms:etag ?__mms_etag ;
            ?org_p ?org_o .
        
        optional {
            ?thing mms:org ?$SPARQL_VAR_NAME_ORG ;
                ?thing_p ?thing_o .
        }
    }
    
    ${permittedActionSparqlBgp(Permission.READ_ORG, Scope.CLUSTER)}
"""

// select ETag(s) of existing org(s)
private val SPARQL_SELECT_ORG_ETAGS = """
    select distinct ?__mms_etag {
        $SPARQL_BGP_ORG
    } order by asc(?__mms_etag)
"""

// construct graph of all relevant org metadata
private val SPARQL_CONSTRUCT_ORG = """
    construct {
        ?$SPARQL_VAR_NAME_ORG ?org_p ?org_o ;
            mms:etag ?__mms_etag .
        
        ?thing ?thing_p ?thing_o .
        
        ?$SPARQL_VAR_NAME_CONTEXT a mms:Context ;
            mms:permit mms-object:Permission.ReadOrg ;
            mms:policy ?policy ;
            .
        
        #?__mms_policy ?__mms_policy_p ?__mms_policy_o .
        
        ?orgPolicy ?orgPolicy_p ?orgPolicy_o .
    } where {
        $SPARQL_BGP_ORG
        
        graph m-graph:AccessControl.Policies {
            #?__mms_policy ?__mms_policy_p ?__mms_policy_o .

            optional {
                ?orgPolicy a mms:Policy ;
                    mms:scope ?$SPARQL_VAR_NAME_ORG ;
                    ?orgPolicy_p ?orgPolicy_o .
            }
        }
    }
"""


/**
 * Fetches the org(s) ETag
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headOrgs(allOrgs: Boolean=false) {
    val orgIri = if(allOrgs) null else prefixes["mo"]!!

    // fetch all orgs
    val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_ORG_ETAGS) {
        acceptReplicaLag = true

        // get by orgId
        orgIri?.let {
            iri(
                SPARQL_VAR_NAME_ORG to it,
            )
        }

        iri(
            SPARQL_VAR_NAME_CONTEXT to "urn:mms:context:$transactionId",
        )
    }

    // parse the result bindings
    val bindings = parseSparqlResultsJson(selectResponseText)

    // hash all the org etags
    handleEtagAndPreconditions(bindings)

    // respond
    call.respond(HttpStatusCode.NoContent)
}


/**
 * Fetches org(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getOrgs(allOrgs: Boolean=false) {
    // cache whether this request is asking for all orgs
    val orgIri = if(allOrgs) null else prefixes["mo"]!!

    // fetch all org details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_ORG) {
        acceptReplicaLag = true

        // get by orgId
        orgIri?.let {
            iri(
                SPARQL_VAR_NAME_ORG to it,
            )
        }

        iri(
            SPARQL_VAR_NAME_CONTEXT to "urn:mms:context:$transactionId",
        )
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

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}

