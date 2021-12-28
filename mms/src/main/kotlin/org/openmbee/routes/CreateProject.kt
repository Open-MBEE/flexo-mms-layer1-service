package org.openmbee.routes

import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*
import org.openmbee.plugins.client
import java.util.*


private val SPARQL_BGP_USER_PERMITTED_CREATE_PROJECT = permittedActionSparqlBgp(Permission.CREATE_PROJECT, Scope.CLUSTER)

private const val SPARQL_BGP_ORG_EXISTS = """
    # org must exist
    graph m-graph:Cluster {
        mo: a mms:Org .
    }
"""

private const val SPARQL_BGP_PROJECT_NOT_EXISTS = """
    # project must not yet exist
    graph m-graph:Cluster {
        filter not exists {
            mp: a mms:Project .
        }
    }
"""

private const val SPARQL_BGP_PROJECT_METADATA_GRAPH_EMPTY = """
    # project metadata graph must be empty
    graph mp-graph:Metadata {
        filter not exists {
            ?s ?p ?o .
        }
    }
"""


private const val SPARQL_CONSTRUCT_TRANSACTION = """
    construct  {
        mp: ?mp_p ?mp_o .
        
        mt: ?mt_p ?mt_o .
    } where {
        graph mp-graph:Metadata {
            mp: ?mp_p ?mp_o .
        }

        graph m-graph:Transactions {
            mt: ?mt_p ?mt_o .
        }
    }
"""

@OptIn(InternalAPI::class)
fun Application.writeProject() {
    routing {
        put("/projects/{projectId}") {
            val projectId = call.parameters["projectId"]!!

            val userId = call.request.headers["mms5-user"]?: ""

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@put;
            }


            val branchId = "main"

            // create transaction context
            val context = TransactionContext(
                userId=userId,
                projectId=projectId,
                branchId=branchId,
                request=call.request,
            )

            // initialize prefixes
            var prefixes = context.prefixes

            // create a working model to prepare the Update
            val workingModel = KModel(prefixes)

            // create project node
            val projectNode = workingModel.createResource(prefixes["mp"])

            // read entire request body
            val requestBody = call.receiveText()

            // read put contents
            parseBody(
                body=requestBody,
                prefixes=prefixes,
                baseIri=projectNode.uri,
                model=workingModel,
            )

            // prepare org resource
            var orgResource: Resource? = null

            // set system-controlled properties and remove conflicting triples from user input
            projectNode.run {
                removeAll(RDF.type)
                removeAll(MMS.id)

                // normalize mms:org
                run {
                    listProperties(MMS.org)
                        // only triples that point to named nodes
                        .filterKeep { it.`object`.isResource }
                        // traverse through them
                        .forEach {
                            // more than one provided
                            if(null != orgResource) {
                                throw InvalidDocumentSemanticsException("The `mms:org` relation has a maximum cardinality of 1; but more than 1 were provided")
                            }
                            else {
                                orgResource = it.`object`.asResource()
                            }
                        }

                    // none provided
                    if(null == orgResource) {
                        throw InvalidDocumentSemanticsException("The `mms:org` relation has a minimum cardinality of 1; but none were provided")
                    }

                    // remove everything
                    removeAll(MMS.org)

                    // add back the approved org
                    addProperty(MMS.org, orgResource)
                }

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

                addProperty(RDF.type, MMS.Project)
                addProperty(MMS.id, projectId)
            }

            // update prefixes with orgId
            val orgId = orgResource!!.uri.replaceFirst(prefixes["m-org"]!!, "")
            context.orgId = orgId
            prefixes = context.prefixes

            // serialize project node
            val projectTriples = KModel(prefixes) {
                removeNsPrefix("m-project")
                add(projectNode.listProperties())
            }.stringify(emitPrefixes=false)

            // generate sparql update
            val sparqlUpdate = context.update {
                insert {
                    txn()

                    graph("m-graph:Cluster") {
                        raw(projectTriples)
                    }

                    graph("mp-graph:Metadata") {
                        raw("""
                            mpb: a mms:Branch ;
                                mms:id ?_branchId ;
                                mms:commit mpc: ;
                                .
                    
                            mpc: a mms:Commit ;
                                mms:parent rdf:nil ;
                                mms:submitted ?_now ;
                                mms:message ?_commitMessage ;
                                mms:data mpc-data: ;
                                .
                    
                            mpc-data: a mms:Dataset ;
                                mms:source <> ;
                                .
                        """)
                    }
                }
                where {
                    raw(listOf(
                        SPARQL_BGP_USER_PERMITTED_CREATE_PROJECT,

                        SPARQL_BGP_ORG_EXISTS,

                        SPARQL_BGP_PROJECT_NOT_EXISTS,

                        SPARQL_BGP_PROJECT_METADATA_GRAPH_EMPTY,
                    ).joinToString("\n"))
                }

                insert {
                    graph("m-graph:AccessControl") {
                        raw("""
                            ?_autoPolicy a mms:Policy ;
                                mms:subject mu: ;
                                mms:scope mms-object:Scope.Project ;
                                mms:role mms-object:Role.AdminMetadata, mms-object:Role.AdminModel ;
                                .
                        """)
                    }
                }
                where {
                    graph("m-graph:Transactions") {
                        raw("""
                            mt: mms:commitId mpc: .
                        """)
                    }
                }
            }.toString {
                iri(
                    "_autoPolicy" to "${prefixes["m-policy"]}AutoProjectOwner.${UUID.randomUUID()}",
                )
            }

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
            val selectResponseText = constructResponse.readText()

            // log
            log.info("Triplestore responded with:\n$selectResponseText")


            // 200 OK
            if(constructResponse.status.isSuccess()) {
                val constructModel = KModel(prefixes)

                // parse model
                parseBody(
                    body=selectResponseText,
                    baseIri=projectNode.uri,
                    model=constructModel,
                )

                // transaction failed
                if(!constructModel.createResource(prefixes["mt"]).listProperties(MMS.commitId).hasNext()) {
                    var reason = "Transaction failed due to an unknown reason"

                    // user
                    if(null != userId) {
                        // user does not exist
                        if(!client.executeSparqlAsk(SPARQL_BGP_USER_EXISTS, prefixes)) {
                            reason = "User <${prefixes["mu"]}> does not exist."
                        }
                        // user does not have permission to create project
                        else if(!client.executeSparqlAsk(SPARQL_BGP_USER_PERMITTED_CREATE_PROJECT, prefixes)) {
                            reason = "User <${prefixes["mu"]}> is not permitted to create projects."

                            // log ask query that fails
                            log.warn("The following ASK query failed as the suspected reason for CreateProject failure: \n${parameterizedSparql(SPARQL_BGP_USER_PERMITTED_CREATE_PROJECT) { prefixes(prefixes) }}")
                        }
                    }

                    // org does not exist
                    if(!client.executeSparqlAsk(SPARQL_BGP_ORG_EXISTS, prefixes)) {
                        reason = "The provided org <${prefixes["mo"]}> does not exist."
                    }
                    // project already exists
                    else if(!client.executeSparqlAsk(SPARQL_BGP_PROJECT_NOT_EXISTS, prefixes)) {
                        reason = "The destination project <${prefixes["mp"]}> already exists."
                    }
                    // project metadata graph not empty
                    else if(!client.executeSparqlAsk(SPARQL_BGP_PROJECT_METADATA_GRAPH_EMPTY, prefixes)) {
                        reason = "The destination project's metadata graph <${prefixes["mp-graph"]}> is not empty."
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
