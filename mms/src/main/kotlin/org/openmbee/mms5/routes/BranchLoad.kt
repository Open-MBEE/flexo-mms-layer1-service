package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.openmbee.mms5.*


private val DEFAULT_UPDATE_CONDITIONS = BRANCH_COMMIT_CONDITIONS


fun Application.loadBranch() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/graph") {
            call.mmsL1(Permission.UPDATE_BRANCH) {
                pathParams {
                    org()
                    repo()
                    branch()
                }

                val model = KModel(prefixes).apply {
                    parseTurtle(requestBody,this)
                    clearNsPrefixMap()
                }

                val localConditions = DEFAULT_UPDATE_CONDITIONS

                val loadUpdateString = buildSparqlUpdate {
                    insert {
                        txn()

                        graph("?_loadGraph") {
                            raw(model.stringify())
                        }
                    }
                    where {
                        raw(*localConditions.requiredPatterns())
                    }
                }

                log.info(loadUpdateString)

                val loadGraphUri = "${prefixes["mor-graph"]}Load.$transactionId"

                executeSparqlUpdate(loadUpdateString) {
                    iri(
                        "_loadGraph" to loadGraphUri,
                    )
                }

                diffId = "Load.$transactionId"

                // gen diff query
                val diffUpdateString = genDiffUpdate("", localConditions)

                executeSparqlUpdate(diffUpdateString) {
                    iri(
                        // use current branch as ref source
                        "refSource" to prefixes["morb"]!!,
                    )
                }

                val diffConstructString = buildSparqlQuery {
                    construct {
                        txn()
                    }
                    where {
                        group {
                            txn()
                        }
                        raw("""
                            union ${localConditions.unionInspectPatterns()}    
                        """)
                    }
                }

                val diffConstructResponseText = executeSparqlConstructOrDescribe(diffConstructString)

                log.info("RESPONSE TXT: $diffConstructResponseText")

                val diffConstructModel = validateTransaction(diffConstructResponseText, localConditions)

                val propertyUriAt = { res: Resource, prop: Property -> diffConstructModel.listObjectsOfProperty(res, prop).next().asResource().uri }

                val transactionNode = diffConstructModel.createResource(prefixes["mt"])

                val diffInsGraph = propertyUriAt(transactionNode, MMS.TXN.diffInsGraph)
                val diffDelGraph = propertyUriAt(transactionNode, MMS.TXN.diffDelGraph)

                val delModel = downloadModel(diffDelGraph)
                delModel.clearNsPrefixMap()
                val delTriples = delModel.stringify()

                val insModel = downloadModel(diffInsGraph)
                insModel.clearNsPrefixMap()
                val insTriples = insModel.stringify()


                val commitUpdateString = genCommitUpdate(
                    delete="""
                        graph ?stagingGraph {
                            $delTriples
                        }
                    """,
                    insert="""
                        graph ?stagingGraph {
                            $insTriples
                        }
                    """,
                    // include conditions in order to match ?stagingGraph
                    where=localConditions.requiredPatterns().joinToString("\n"),
                )

                log.info(commitUpdateString)

                call.respondText("")


                executeSparqlUpdate(commitUpdateString) {
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


                // executeSparqlUpdate(commitUpdateString)


                // TODO: convert diff graphs into SPARQL UPDATE string
                // TODO: invoke commitBranch() using update
            }
        }
    }
}