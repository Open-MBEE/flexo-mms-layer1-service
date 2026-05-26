package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_COLLECTION
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_ORG
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse
import org.openmbee.flexo.mms.server.LdpReadResponse


// reusable basic graph pattern for matching collection(s)
private val SPARQL_BGP_COLLECTION: (Boolean, Boolean) -> String = { allCollections, allData -> """
    graph m-graph:Cluster {
        ${"optional {" iff allCollections}${"""
            ?$SPARQL_VAR_NAME_COLLECTION a mms:Collection ;
                mms:etag ?__mms_etag ;
                mms:org ?$SPARQL_VAR_NAME_ORG ;
                ${"?collection_p ?collection_o ;" iff allData}
                .
        """.reindent(if(allCollections) 3 else 2)}
        ${"}" iff allCollections}
    }
    
    ${permittedActionSparqlBgp(Permission.READ_COLLECTION, Scope.COLLECTION,
        if(allCollections) "^moc:?$".toRegex() else null,
        if(allCollections) "" else null,
        scopeJoinVars = if(allCollections) listOf(SPARQL_VAR_NAME_COLLECTION) else null)}
""" }

// construct graph of all relevant collection metadata
private val SPARQL_CONSTRUCT_COLLECTION: (Boolean, Boolean) -> String = { allCollections, allData -> """
    construct {
        ?$SPARQL_VAR_NAME_COLLECTION a mms:Collection ;
            mms:etag ?__mms_etag ;
            ?collection_p ?collection_o ;
            .
        ${generateReadContextBgp(Permission.READ_COLLECTION).reindent(2)}
    } where {
        ${SPARQL_BGP_COLLECTION(allCollections, allData).reindent(2)}
    }
""" }


/**
 * Fetches collection(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpReadResponse>.fetchCollections(allCollections: Boolean=false, allData: Boolean=false): String {
    // cache whether this request is asking for all collections
    val collectionIri = if(allCollections) null else prefixes["moc"]!!

    // fetch all collection details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_COLLECTION(allCollections, allData)) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // always belongs to some org
        iri(
            SPARQL_VAR_NAME_ORG to prefixes["mo"]!!,
        )

        // get by collectionId
        collectionIri?.let {
            iri(
                SPARQL_VAR_NAME_COLLECTION to it,
            )
        }
    }

    // parse the response
    parseConstructResponse(constructResponseText) {
        // hash all the collection etags
        if(allCollections) {
            handleEtagAndPreconditions(model, MMS.Collection)
        }
        // just the individual collection
        else {
            handleEtagAndPreconditions(model, collectionIri)
        }
    }

    return constructResponseText
}

/**
 * Tests access to the collection(s)
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headCollections(allCollections: Boolean=false) {
    fetchCollections(allCollections, false)

    // respond
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Fetches collection(s) metadata and all relevant data to satisfy a GET request
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getCollections(allCollections: Boolean=false) {
    val constructResponseText = fetchCollections(allCollections, true)

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}
