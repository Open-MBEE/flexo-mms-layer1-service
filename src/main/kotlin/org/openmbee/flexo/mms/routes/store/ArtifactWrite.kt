package org.openmbee.flexo.mms.routes.store

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.http.headers
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

    // extend the default conditions with requirements for user-specified ref or commit
    val localConditions = REPO_CRUD_CONDITIONS.append {
        // require that the user has the ability to create objects on a repo-level scope
        permit(Permission.CREATE_ARTIFACT, Scope.REPO)
    }

    var storage = ""
    if (call.application.artifactUseStore) { //use store service
        val path = "$orgId/$repoId/$transactionId"
        var storeServiceUrl: String? = call.application.storeServiceUrl
        val response: HttpResponse = defaultHttpClient.put("$storeServiceUrl/$path") {
            headers {
                call.request.headers[HttpHeaders.Authorization]?.let { auth: String ->
                    append(HttpHeaders.Authorization, auth)
                }
            }
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = call.request.contentType()
                override val contentLength = call.request.contentLength() ?: 0L //TODO make it so client isn't required to send this
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    call.request.receiveChannel().copyTo(channel)
                }
            })
        }
        storage = "mms:body ${escapeLiteral(path)}^^xsd:anyURI"
    } else {
        if (requestBodyContentType.startsWith("text")) {
            val body = call.receiveText()
            storage = "mms:body ${escapeLiteral(body)}"
        } else {
            val body = Base64.getEncoder().encodeToString(call.receive<ByteArray>())
            storage = "mms:body ${escapeLiteral(body)}^^xsd:base64Binary"

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
                        $storage ; 
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
            group {
                txn(null, "mora")

                raw("""
                    graph mor-graph:Artifacts {
                        ?$SPARQL_VAR_NAME_ARTIFACT a mms:Artifact ;
                            ?mora_p ?mora_o .
                    }
                """)
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // execute construct
    val constructResponseText = executeSparqlConstructOrDescribe(constructString) {
        prefixes(prefixes)

        iri(
            SPARQL_VAR_NAME_ARTIFACT to artifactIri
        )
    }

    // log
    log.info("Finalizing write transaction...\n######## request: ########\n$constructString\n\n######## response: ########\n$constructResponseText")

    // validate whether the transaction succeeded
    val constructModel = validateTransaction(constructResponseText, localConditions, null, "mora")

    // set location header
    call.response.headers.append(HttpHeaders.Location, artifactIri)

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
