package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.openmbee.mms5.*


private val DEFAULT_UPDATE_CONDITIONS = BRANCH_COMMIT_CONDITIONS


fun Route.loadModel() {
    post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/graph") {
        call.mmsL1(Permission.UPDATE_BRANCH) {
            // check query parameter(s)
            val loadUrl = call.request.queryParameters["url"]?: throw Http400Exception("Requisite {url} query parameter is missing")

            // check path parameters
            pathParams {
                org()
                repo()
                branch()
            }

            // set diff id
            diffId = "Load.$transactionId"

            // prepare IRI for named graph to hold loaded model
            val loadGraphUri = "${prefixes["mor-graph"]}Load.$transactionId"

            // prep conditions
            val localConditions = DEFAULT_UPDATE_CONDITIONS.append {
                // assert HTTP preconditions
                assertPreconditions(this) {
                    """
                        graph mor-graph:Metadata {
                            morb: mms:etag ?etag .
                            
                            $it
                        }
                    """
                }
            }

            // prepare txn for loading triples via SPARQL LOAD into new graph
            run {
                val loadUpdateString = buildSparqlUpdate {
                    insert {
                        txn()
                    }
                    where {
                        raw(*localConditions.requiredPatterns())
                    }

                    raw("""
                        ; load ?_loadUrl into graph ?_loadGraph
                    """)
                }

                log.info(loadUpdateString)

                executeSparqlUpdate(loadUpdateString) {
                    prefixes(prefixes)

                    iri(
                        "_loadUrl" to loadUrl,
                        "_loadGraph" to loadGraphUri,
                    )
                }
            }


            // check if load was successful
            run {
                val loadConstructString = buildSparqlQuery {
                    construct {
                        txn()
                    }
                    where {
                        group {
                            txn()
                        }
                        raw(
                            """
                            union ${localConditions.unionInspectPatterns()}    
                        """
                        )
                    }
                }

                val loadConstructResponseText = executeSparqlConstructOrDescribe(loadConstructString)

                validateTransaction(loadConstructResponseText, localConditions)
            }


            // compute the delta
            run {
                val updateString = genDiffUpdate("", localConditions, """
                    graph mor-graph:Metadata {
                        # select the latest commit from the current named ref
                        ?srcRef mms:commit ?srcCommit .
                        
                        ?srcCommit ^mms:commit/mms:snapshot ?srcSnapshot .
                        
                        ?srcSnapshot a mms:Model ; 
                            mms:graph ?srcGraph  .
                    }
                """)

                executeSparqlUpdate(updateString) {
                    prefixes(prefixes)

                    iri(
                        // use current branch as ref source
                        "srcRef" to prefixes["morb"]!!,

                        // set dst graph
                        "dstGraph" to loadGraphUri,
                    )
                }
            }

            // validate diff creation
            lateinit var diffConstructResponseText: String
            val diffConstructModel = run {
                val diffConstructString = buildSparqlQuery {
                    construct {
                        txn()
                        etag("morb:")
                    }
                    where {
                        group {
                            txn()
                            etag("morb:")
                        }
                        raw(
                            """
                            union ${localConditions.unionInspectPatterns()}    
                        """
                        )
                    }
                }

                diffConstructResponseText = executeSparqlConstructOrDescribe(diffConstructString)

                log.info("RESPONSE TXT: $diffConstructResponseText")

                validateTransaction(diffConstructResponseText, localConditions)
            }

            // shortcut lambda for fetching properties in returned model
            val propertyUriAt = { res: Resource, prop: Property ->
                diffConstructModel.listObjectsOfProperty(res, prop).let {
                    if(it.hasNext()) it.next().asResource().uri else null
                }
            }

            // locate transaction node
            val transactionNode = diffConstructModel.createResource(prefixes["mt"])

            // get ins/del graphs
            val diffInsGraph = propertyUriAt(transactionNode, MMS.TXN.diffInsGraph)
            val diffDelGraph = propertyUriAt(transactionNode, MMS.TXN.diffDelGraph)

            // download & stringify graph of deleted triples
            val delTriples = if(diffDelGraph != null) {
                val delModel = downloadModel(diffDelGraph)
                delModel.clearNsPrefixMap()
                delModel.stringify()
            } else ""

            // download & stringify graph of inserted triples
            val insTriples = if(diffInsGraph != null) {
                val insModel = downloadModel(diffInsGraph)
                insModel.clearNsPrefixMap()
                insModel.stringify()
            } else ""


            // delete transaction from triplestore
            executeSparqlUpdate("""
                delete where {
                    graph m-graph:Transactions {
                        mt: ?p ?o .
                    }
                }
            """)


            // empty delta (no changes)
            if(delTriples.isBlank() && insTriples.isBlank()) {
                // locate branch node
                val branchNode = diffConstructModel.createResource(prefixes["morb"])

                // get etag value
                val etagValue = branchNode.getProperty(MMS.etag).`object`.asLiteral().string

                // set etag header
                call.response.header(HttpHeaders.ETag, etagValue)

                // respond
                call.respondText("")

                // done
                return@mmsL1
            }


            // TODO add condition to update that the selected staging has not changed since diff creation using etag value

            // perform update commit
            run {
                val commitUpdateString = genCommitUpdate(
                    delete = """
                        graph ?stagingGraph {
                            $delTriples
                        }
                    """,
                    insert = """
                        graph ?stagingGraph {
                            $insTriples
                        }
                    """,
                    // include conditions in order to match ?stagingGraph
                    where = localConditions.requiredPatterns().joinToString("\n"),
                )

                log.info(commitUpdateString)


                executeSparqlUpdate(commitUpdateString) {
                    prefixes(prefixes)

                    iri(
                        "_interim" to "${prefixes["mor-lock"]}Interim.${transactionId}",
                    )

                    datatyped(
                        "_updateBody" to ("" to MMS_DATATYPE.sparql),
                        "_patchString" to ("""
                            delete {
                                graph ?__mms_graph {
                                    $delTriples
                                }
                            }
                            insert {
                                graph ?__mms_graph {
                                    $insTriples
                                }
                            }
                        """ to MMS_DATATYPE.sparql),
                        "_whereString" to ("" to MMS_DATATYPE.sparql),
                    )

                    literal(
                        "_txnId" to transactionId,
                    )
                }
            }


            // done
            call.respondText(diffConstructResponseText)
        }
    }
}