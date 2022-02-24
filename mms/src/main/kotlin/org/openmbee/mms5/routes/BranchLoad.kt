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

                diffId = "Load.$transactionId"

                val loadGraphUri = "${prefixes["mor-graph"]}Load.$transactionId"

                val localConditions = DEFAULT_UPDATE_CONDITIONS

                val model = KModel(prefixes).apply {
                    parseTurtle(requestBody,this)
                    clearNsPrefixMap()
                }


                // load triples from request body into new graph
                run {
                    val loadUpdateString = buildSparqlUpdate {
                        insert {
                            txn()

                            // embed the model in a triples block within the update
                            graph("?_loadGraph") {
                                raw(model.stringify())
                            }
                        }
                        where {
                            raw(*localConditions.requiredPatterns())
                        }
                    }

                    log.info(loadUpdateString)

                    executeSparqlUpdate(loadUpdateString) {
                        iri(
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
                    val diffUpdateString = buildSparqlUpdate {
                        insert {
                            txn(
                                "mms-txn:stagingGraph" to "?stagingGraph",
                                "mms-txn:srcGraph" to "?srcGraph",
                                "mms-txn:dstGraph" to "?dstGraph",
                                "mms-txn:diffInsGraph" to "?diffInsGraph",
                                "mms-txn:diffDelGraph" to "?diffDelGraph",
                            ) {
                                autoPolicy(Scope.DIFF, Role.ADMIN_DIFF)
                            }

                            raw(
                                """
                                graph ?diffInsGraph {
                                    ?ins_s ?ins_p ?ins_o .    
                                }
                                
                                graph ?diffDelGraph {
                                    ?del_s ?del_p ?del_o .
                                }
                                
                                graph mor-graph:Metadata {
                                    ?diff mms:id ?diffId ;
                                        mms:diffSrc ?commitSource ;
                                        mms:diffDst morc: ;
                                        mms:insGraph ?diffInsGraph ;
                                        mms:delGraph ?diffDelGraph ;
                                        .
                                }
                            """
                            )
                        }
                        where {
                            raw(
                                """
                                graph mor-graph:Metadata {
                                    # select the latest commit from the current named ref
                                    morb: mms:commit ?commitSource .
                                        
                                    ?commitSource ^mms:commit/mms:snapshot ?snapshot .
                                    
                                    ?snapshot a mms:Model ; 
                                        mms:graph ?srcGraph  .
                                }
                                
                                bind(
                                    sha256(
                                        concat(str(morc:), "\n", str(?commitSource))
                                    ) as ?diffId
                                )
                                
                                bind(
                                    iri(
                                        concat(str(morc:), "/diffs/", ?diffId)
                                    ) as ?diff
                                )
                                
                                bind(
                                    iri(
                                        concat(str(mor-graph:), "Diff.Ins.", ?diffId)
                                    ) as ?diffInsGraph
                                )
                                
                                bind(
                                    iri(
                                        concat(str(mor-graph:), "Diff.Del.", ?diffId)
                                    ) as ?diffDelGraph
                                )
    
    
                                {
                                    graph ?srcGraph {
                                        ?ins_s ?ins_p ?ins_o .
                                    }
                                    
                                    filter not exists {
                                        graph ?dstGraph {
                                            ?ins_s ?ins_p ?ins_o .
                                        }
                                    }
                                } union {                            
                                    graph ?dstGraph {
                                        ?del_s ?del_p ?del_o .
                                    }
                                    
                                    filter not exists {
                                        graph ?srcGraph {
                                            ?del_s ?del_p ?del_o .
                                        }
                                    }
                                }
                            """
                            )
                        }
                    }

                    executeSparqlUpdate(diffUpdateString) {
                        iri(
                            // use current branch as ref source
                            "refSource" to prefixes["morb"]!!,

                            // set dst graph
                            "dstGraph" to loadGraphUri,
                        )
                    }
                }

                // validate diff creation
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


                val propertyUriAt = { res: Resource, prop: Property ->
                    diffConstructModel.listObjectsOfProperty(res, prop).let {
                        if(it.hasNext()) it.next().asResource().uri else null
                    }
                }

                val transactionNode = diffConstructModel.createResource(prefixes["mt"])

                val diffInsGraph = propertyUriAt(transactionNode, MMS.TXN.diffInsGraph)
                val diffDelGraph = propertyUriAt(transactionNode, MMS.TXN.diffDelGraph)


                val delTriples = if(diffDelGraph != null) {
                    val delModel = downloadModel(diffDelGraph)
                    delModel.clearNsPrefixMap()
                    delModel.stringify()
                } else ""

                val insTriples = if(diffInsGraph != null) {
                    val insModel = downloadModel(diffInsGraph)
                    insModel.clearNsPrefixMap()
                    insModel.stringify()
                } else ""


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
}