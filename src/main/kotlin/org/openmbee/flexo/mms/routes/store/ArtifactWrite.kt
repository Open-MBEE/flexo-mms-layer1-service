package org.openmbee.flexo.mms.routes.store

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_ARTIFACT
import org.openmbee.flexo.mms.server.GenericRequest
import org.openmbee.flexo.mms.server.StorageAbstractionPostResponse
import java.util.*

suspend fun<TRequestContext: GenericRequest> Layer1Context<TRequestContext, StorageAbstractionPostResponse>.createArtifact() {
    // forbid wildcards
    if(requestBodyContentType.contains("*")) {
        throw UnsupportedMediaType("Wildcards not allowed")
    }

    // extend the default conditions
    val localConditions = REPO_CRUD_CONDITIONS.append {
        // require that the user has the ability to create artifacts on a repo-level scope
        permit(Permission.CREATE_ARTIFACT, Scope.REPO)
    }

    // prep storage triple fragment
    var storageFragment = ""

    // use store service
    val storeServiceUrl: String? = call.application.storeServiceUrl
    if (storeServiceUrl != null) {
        val path = "$orgId/$repoId/$transactionId"

        // submit to store service
        val response: HttpResponse = defaultHttpClient.put("$storeServiceUrl/$path") {
            headers {
                // add auth header
                call.request.headers[HttpHeaders.Authorization]?.let { auth: String ->
                    append(HttpHeaders.Authorization, auth)
                }
            }
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = call.request.contentType()
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    call.request.receiveChannel().copyTo(channel)
                }
            })
        }

        // set storage details
        storageFragment = "mms:body ${escapeLiteral(path)}^^xsd:anyURI"
    } else {
        if (requestBodyContentType.startsWith("text")) {
            val body = call.receiveText()
            storageFragment = "mms:body ${escapeLiteral(body)}"
        } else {
            val body = Base64.getEncoder().encodeToString(call.receive<ByteArray>())
            storageFragment = "mms:body ${escapeLiteral(body)}^^xsd:base64Binary"
        }
    }

    // create update SPARQL
    val updateString = buildSparqlUpdate {
        insert {
            txn {}

            graph("mor-graph:Artifacts") {
                raw("""
                    ?$SPARQL_VAR_NAME_ARTIFACT a mms:Artifact ;
                        mms:id ${escapeLiteral(transactionId)} ;
                        mms:created ?_now ;
                        mms:createdBy mu: ;
                        mms:contentType ${escapeLiteral(requestBodyContentType)} ;
                        $storageFragment ; 
                        .
                """)
            }
        }
        where {
            raw(*localConditions.requiredPatterns())
        }
    }

    // create object IRI
    val artifactIri = "${prefixes["mor-artifact"]}$transactionId"

    // execute update
    executeSparqlUpdate(updateString) {
        prefixes(prefixes)

        iri(
            SPARQL_VAR_NAME_ARTIFACT to artifactIri
        )
    }

    // create construct query to confirm transaction and fetch artifact details
    val constructString = buildSparqlQuery {
        construct {
            // all details about this transaction
            txn()

            // all properties about this artifact
            raw("""
                # outgoing artifact properties
                ?$SPARQL_VAR_NAME_ARTIFACT ?mora_p ?mora_o .
            """)
        }
        where {
            txnOrInspections(null, localConditions) {
                raw("""
                    graph mor-graph:Artifacts {
                        ?$SPARQL_VAR_NAME_ARTIFACT a mms:Artifact ;
                            ?mora_p ?mora_o .
                    }
                """)
            }
        }
    }


    // execute construct
    val constructResponseText = executeSparqlConstructOrDescribe(constructString) {
        prefixes(prefixes)

        iri(
            SPARQL_VAR_NAME_ARTIFACT to artifactIri
        )
    }

    // validate whether the transaction succeeded
    val constructModel = validateTransaction(constructResponseText, localConditions, null, "mora")

    // set location header
    call.response.headers.append(HttpHeaders.Location, artifactIri)

    // Can't get just the content-type without the parameter, need to parse manually
    call.response.headers.append(HttpHeaders.ContentType, call.request.contentType().withoutParameters().toString())

//    // response in the requested format
//    call.respondText(constructModel.stringify(), RdfContentTypes.Turtle, HttpStatusCode.Created)

    // close response with 201
    call.respond(HttpStatusCode.Created)

    // delete transaction
    run {
        // submit update
        val dropResponseText = executeSparqlUpdate("""
            delete where {
                graph m-graph:Transactions {
                    mt: ?p ?o .
                }
            }
        """)

        // log response
        log.info("Delete transaction response:\n$dropResponseText")
    }
}
