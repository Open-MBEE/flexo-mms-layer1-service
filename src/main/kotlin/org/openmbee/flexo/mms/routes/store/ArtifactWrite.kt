package org.openmbee.flexo.mms.routes.store

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_ARTIFACT
import org.openmbee.flexo.mms.server.GenericRequest
import org.openmbee.flexo.mms.server.StorageAbstractionPostResponse

suspend fun<TRequestContext: GenericRequest> Layer1Context<TRequestContext, StorageAbstractionPostResponse>.createArtifact() {
    // forbid wildcards
    if(requestBodyContentType.contains("*")) {
        throw UnsupportedMediaType("Wildcards not allowed")
    }

    // extend the default conditions with requirements for user-specified ref or commit
    val localConditions = REPO_CRUD_CONDITIONS.append {
        // require that the user has the ability to create objects on a repo-level scope
        permit(Permission.CREATE_ARTIFACT, Scope.REPO)
    }

    // fully load request body
    val body = call.receiveText()

    // create update SPARQL
    val updateString = buildSparqlUpdate {
        insert {
            graph("mor-graph:Objects") {
                raw(
                    """
                ?$SPARQL_VAR_NAME_ARTIFACT a mms:Object ;
                    mms:contentType ${escapeLiteral(requestBodyContentType)} ;
                    mms:body ${escapeLiteral(body)} ;
                    .
            """
                )
            }
        }
        where {
            raw(*localConditions.requiredPatterns())
        }
    }


    // create object IRI
    val objectIri = "${prefixes["mor-object"]}$transactionId"

    // execute update
    executeSparqlUpdate(updateString) {
        prefixes(prefixes)

        iri(
            SPARQL_VAR_NAME_ARTIFACT to objectIri
        )
    }

    // set location header
    call.response.headers.append(HttpHeaders.Location, objectIri)

    // created
    call.respond(HttpStatusCode.Created)
}
