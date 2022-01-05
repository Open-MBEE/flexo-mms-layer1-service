package org.openmbee.routes

import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*
import org.openmbee.plugins.client
import java.util.*

private val SPARQL_BGP_USER_PERMITTED_CREATE_ORG = permittedActionSparqlBgp(Permission.CREATE_ORG, Scope.CLUSTER)

private const val SPARQL_BGP_ORG_NOT_EXISTS = """
    # org must not yet exist
    graph m-graph:Cluster {
        filter not exists {
            mo: a mms:Org .
        }
    }
"""

private const val SPARQL_CONSTRUCT_TRANSACTION = """
    construct  {
        mo: ?mo_p ?mo_o .
        
        mt: ?mt_p ?mt_o .
    } where {
        graph m-graph:Cluster {
            mo: ?mo_p ?mo_o .
        }

        graph m-graph:Transactions {
            mt: ?mt_p ?mt_o .
        }
    }
"""


@OptIn(InternalAPI::class)
fun Application.writeOrg() {
    routing {
        put("/orgs/{orgId}") {
            val orgId = call.parameters["orgId"]!!

            val userId = call.request.headers["mms5-user"]?: ""

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@put;
            }

            // read request body
            val requestBody = call.receiveText()

            // create transaction context
            val context = TransactionContext(
                userId=userId,
                orgId=orgId,
                request=call.request,
                requestBody=requestBody,
            )

            // initialize prefixes
            var prefixes = context.prefixes

            // create a working model to prepare the Update
            val workingModel = KModel(prefixes)

            // create org node
            val orgNode = workingModel.createResource(prefixes["mo"])

            // read put contents
            parseBody(
                body=requestBody,
                prefixes=prefixes,
                baseIri=orgNode.uri,
                model=workingModel,
            )

            // set system-controlled properties and remove conflicting triples from user input
            orgNode.run {
                removeAll(RDF.type)
                removeAll(MMS.id)

                // normalize dct:title
                run {
                    // remove triples that don't point to literals
                    listProperties(DCTerms.title)
                        .forEach {
                            if(!it.`object`.isLiteral) {
                                workingModel.remove(it)
                            }
                        }
                }

                addProperty(RDF.type, MMS.Org)
                addProperty(MMS.id, orgId)
            }

            // serialize org node
            val orgTriples = KModel(prefixes) {
                removeNsPrefix("m-org")
                add(orgNode.listProperties())
            }.stringify(emitPrefixes=false)

            // generate sparql update
            val sparqlUpdate = context.update {
                insert {
                    txn()

                    graph("m-graph:Cluster") {
                        raw(orgTriples)
                    }

                    // auto-policy
                    autoPolicySparqlBgp(
                        builder=this,
                        prefixes=prefixes,
                        scope=Scope.ORG,
                        roles=listOf(Role.ADMIN_METADATA, Role.ADMIN_MODEL),
                    )
                }
                where {
                    raw(
                        SPARQL_BGP_USER_PERMITTED_CREATE_ORG,
                        SPARQL_BGP_ORG_NOT_EXISTS,
                    )
                }
            }.toString()


            // log
            log.info(sparqlUpdate)

            // submit update
            val updateResponse = client.submitSparqlUpdate(sparqlUpdate)


            // create construct query to confirm transaction and fetch project details
            val sparqlConstruct = parameterizedSparql(SPARQL_CONSTRUCT_TRANSACTION) {
                prefixes(context.prefixes)
            }

            // log
            log.info(sparqlConstruct)

            // fetch transaction results
            val constructResponse = client.submitSparqlConstruct(sparqlConstruct)

            // download select results
            val constructResponseText = constructResponse.readText()

            // log
            log.info("Triplestore responded with:\n$constructResponseText")


            // 200 OK
            if(constructResponse.status.isSuccess()) {
                val constructModel = KModel(prefixes)

                // parse model
                parseBody(
                    body=constructResponseText,
                    baseIri=orgNode.uri,
                    model=constructModel,
                )

                // transaction failed
                if(!constructModel.createResource(prefixes["mt"]).listProperties(RDF.type).hasNext()) {
                    var reason = "Transaction failed due to an unknown reason"

                    // user
                    if(null != userId) {
                        // user does not exist
                        if(!client.executeSparqlAsk(SPARQL_BGP_USER_EXISTS, prefixes)) {
                            reason = "User <${prefixes["mu"]}> does not exist."
                        }
                        // user does not have permission to create project
                        else if(!client.executeSparqlAsk(SPARQL_BGP_USER_PERMITTED_CREATE_ORG, prefixes)) {
                            reason = "User <${prefixes["mu"]}> is not permitted to create orgs."

                            // log ask query that fails
                            log.warn("The following ASK query failed as the suspected reason for CreateOrg failure: \n${parameterizedSparql("\nask {\n$SPARQL_BGP_USER_PERMITTED_CREATE_ORG\n}") { prefixes(prefixes) }}")
                        }
                    }

                    // org already exists
                    if(!client.executeSparqlAsk(SPARQL_BGP_ORG_NOT_EXISTS, prefixes)) {
                        reason = "The provided org <${prefixes["mo"]}> already exists."
                    }

                    call.respondText(reason, status=HttpStatusCode.InternalServerError, contentType=ContentType.Text.Plain)
                    return@put
                }
            }

            // respond
            call.respondText(constructResponseText, status=constructResponse.status, contentType=constructResponse.contentType())

            // delete transaction graph
            run {
                // prepare SPARQL DROP
                val sparqlDrop = parameterizedSparql("""
                    delete where {
                        graph m-graph:Transactions {
                            mt: ?p ?o .
                        }
                    }
                """.trimIndent()) {
                    prefixes(prefixes)
                }

                // log update
                log.info(sparqlDrop)

                // submit update
                val dropResponse = client.submitSparqlUpdate(sparqlDrop)

                // log response
                log.info(dropResponse.readText())
            }
        }
    }
}