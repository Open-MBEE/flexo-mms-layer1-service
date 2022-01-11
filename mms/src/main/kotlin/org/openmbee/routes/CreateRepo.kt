package org.openmbee.routes

import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.impl.ResourceImpl
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*
import org.openmbee.plugins.client
import java.util.*


private val SPARQL_BGP_USER_PERMITTED_CREATE_REPO = permittedActionSparqlBgp(Permission.CREATE_REPO, Scope.REPO)

private const val SPARQL_BGP_ORG_EXISTS = """
    # org must exist
    graph m-graph:Cluster {
        mo: a mms:Org .
    }
"""

private const val SPARQL_BGP_REPO_NOT_EXISTS = """
    # repo must not yet exist
    graph m-graph:Cluster {
        filter not exists {
            mor: a mms:Repo .
        }
    }
"""

private const val SPARQL_BGP_REPO_METADATA_GRAPH_EMPTY = """
    # repo metadata graph must be empty
    graph mor-graph:Metadata {
        filter not exists {
            ?s ?p ?o .
        }
    }
"""


private const val SPARQL_CONSTRUCT_TRANSACTION = """
    construct  {
        mor: ?mor_p ?mor_o .
        
        ?thing ?thing_p ?thing_o .
        
        ?m_s ?m_p ?m_o .
        
        mt: ?mt_p ?mt_o .
    } where {
        graph m-graph:Cluster {
            mor: a mms:Repo ;
                ?mor_p ?mor_o ;
                .

            optional {
                ?thing mms:repo mor: ; 
                    ?thing_p ?thing_o .
            }
        }
    
        graph mor-graph:Metadata {
            ?m_s ?m_p ?m_o .
        }

        graph m-graph:Transactions {
            mt: ?mt_p ?mt_o .
        }
    }
"""

@OptIn(InternalAPI::class)
fun Application.createRepo() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}") {
            val orgId = call.parameters["orgId"]!!
            val repoId = call.parameters["repoId"]!!

            val userId = call.request.headers["mms5-user"]?: ""

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@put
            }


            val branchId = "main"

            // create transaction context
            val context = TransactionContext(
                userId=userId,
                orgId=orgId,
                repoId=repoId,
                branchId=branchId,
                request=call.request,
            )

            // initialize prefixes
            var prefixes = context.prefixes

            // create a working model to prepare the Update
            val workingModel = KModel(prefixes)

            // create org node
            var orgNode = workingModel.createResource(prefixes["mo"])

            // create repo node
            val repoNode = workingModel.createResource(prefixes["mor"])

            // read entire request body
            val requestBody = call.receiveText()

            // read put contents
            parseBody(
                body=requestBody,
                prefixes=prefixes,
                baseIri=repoNode.uri,
                model=workingModel,
            )

            // set system-controlled properties and remove conflicting triples from user input
            repoNode.run {
                removeAll(RDF.type)
                removeAll(MMS.id)
                removeAll(MMS.org)

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

                // add back the approved properties
                addProperty(RDF.type, MMS.Repo)
                addProperty(MMS.id, repoId)
                addProperty(MMS.org, orgNode)
            }

            // serialize repo node
            val repoTriples = KModel(prefixes) {
                add(repoNode.listProperties())
            }.stringify(emitPrefixes=false)

            // generate sparql update
            val sparqlUpdate = context.update {
                insert {
                    txn()

                    graph("m-graph:Cluster") {
                        raw(repoTriples)
                    }

                    graph("mor-graph:Metadata") {
                        raw("""
                            morb: a mms:Branch ;
                                mms:id ?_branchId ;
                                mms:commit morc: ;
                                .
                    
                            morc: a mms:Commit ;
                                mms:parent rdf:nil ;
                                mms:submitted ?_now ;
                                mms:message ?_commitMessage ;
                                mms:data morc-data: ;
                                .
                    
                            morc-data: a mms:Load ;
                                .
                            
                            mor-lock:_MMS_INIT.REPO a mms:Lock ;
                                mms:commit morc: ;
                                mms:model ?_model ;
                                .
                            
                            ?_model a mms:Model ;
                                .
                        """)
                    }

                    autoPolicySparqlBgp(
                        builder=this,
                        prefixes=prefixes,
                        scope=Scope.REPO,
                        roles=listOf(Role.ADMIN_REPO),
                    )
                }
                where {
                    raw(listOf(
                        SPARQL_BGP_USER_PERMITTED_CREATE_REPO,

                        SPARQL_BGP_ORG_EXISTS,

                        SPARQL_BGP_REPO_NOT_EXISTS,

                        SPARQL_BGP_REPO_METADATA_GRAPH_EMPTY,
                    ).joinToString("\n"))
                }
            }.toString {
                iri(
                    "_model" to "${prefixes["mor-graph"]}Model._MMS_INIT.REPO",
                )
            }

            // log
            log.info(sparqlUpdate)

            // submit update
            val updateResponse = client.submitSparqlUpdate(sparqlUpdate)


            // create construct query to confirm transaction and fetch repo details
            val sparqlConstruct = parameterizedSparql(SPARQL_CONSTRUCT_TRANSACTION) {
                prefixes(context.prefixes)
            }

            // log
            log.info(sparqlConstruct)

            // fetch transaction results
            val constructResponse = client.submitSparqlConstruct(sparqlConstruct)

            // download select results
            val selectResponseText = constructResponse.readText()

            // log
            log.info("Triplestore responded with:\n$selectResponseText")


            // 200 OK
            if(constructResponse.status.isSuccess()) {
                val constructModel = KModel(prefixes)

                // parse model
                parseBody(
                    body=selectResponseText,
                    baseIri=repoNode.uri,
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
                        // user does not have permission to create repo
                        else if(!client.executeSparqlAsk(SPARQL_BGP_USER_PERMITTED_CREATE_REPO, prefixes)) {
                            reason = "User <${prefixes["mu"]}> is not permitted to create repos."

                            // log ask query that fails
                            log.warn("The following ASK query failed as the suspected reason for CreateRepo failure: \n${parameterizedSparql(SPARQL_BGP_USER_PERMITTED_CREATE_REPO) { prefixes(prefixes) }}")
                        }
                    }

                    // org does not exist
                    if(!client.executeSparqlAsk(SPARQL_BGP_ORG_EXISTS, prefixes)) {
                        reason = "The provided org <${prefixes["mo"]}> does not exist."
                    }
                    // repo already exists
                    else if(!client.executeSparqlAsk(SPARQL_BGP_REPO_NOT_EXISTS, prefixes)) {
                        reason = "The destination repo <${prefixes["mr"]}> already exists."
                    }
                    // repo metadata graph not empty
                    else if(!client.executeSparqlAsk(SPARQL_BGP_REPO_METADATA_GRAPH_EMPTY, prefixes)) {
                        reason = "The destination repo's metadata graph <${prefixes["mr-graph"]}> is not empty."
                    }


                    call.respondText(reason, status=HttpStatusCode.InternalServerError, contentType=ContentType.Text.Plain)
                    return@put
                }
            }

            // respond
            call.respondText(selectResponseText, status=constructResponse.status, contentType=constructResponse.contentType())

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
