package org.openmbee.flexo.mms.routes.ldp

import io.ktor.server.request.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.GenericRequest
import org.openmbee.flexo.mms.server.LdpPatchResponse
import org.openmbee.flexo.mms.server.LdpPostResponse

val FORBIDDEN_ARTIFACT_SUBJECT_PREFIXES = "^".toRegex()

suspend fun<TRequestContext: GenericRequest> Layer1Context<TRequestContext, LdpPatchResponse>.patchArtifactsMetadata() {
    throw NotImplementedException("patch metadata artifact")
}

suspend fun<TRequestContext: GenericRequest> Layer1Context<TRequestContext, LdpPostResponse>.postArtifactMetadata() {
    val baseIri = prefixes["mor-artifact"]!!
    val content = "${prefixes}\n${call.receiveText()}"

    // create model to filter user input and stringify to triples
    val triples = KModel(prefixes) {
        // load user data
        parseTurtle(content, this, baseIri)

        // each statement
        for (stmt in this.listStatements()) {
            // forbid anything that is not an IRI
            if(!stmt.subject.isURIResource) {
                throw BlankNodesNotAllowedException()
            }

            // get resource IRI
            val uri = stmt.subject.asResource().uri

            // forbidden prefix
            if(uri.startsWith("urn:mms:") || uri.startsWith(ROOT_CONTEXT) || uri.startsWith(OPENMBEE_MMS_RDF)) {
                throw ForbiddenPrefixException(uri)
            }
        }
    }.stringify()

    // build SPARQL update string with INSERT DATA on Artifacts metadata graph
    val updateString = buildSparqlUpdate {
        insertData {
            graph("mor-graph:Artifacts") {
                raw(triples)
            }
        }
    }

    // execute the update
    executeSparqlUpdate(updateString)

    throw NotImplementedException("posting metadata artifact")
}
