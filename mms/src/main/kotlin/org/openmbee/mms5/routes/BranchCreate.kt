package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*


private val DEFAULT_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_BRANCH, Scope.REPO)

    require("branchNotExists") {
        handler = { prefixes -> "The provided branch <${prefixes["morb"]}> already exists." }

        """
            # branch must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    morb: a mms:Branch .
                }
            }
        """
    }
}


fun Application.createBranch() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/graph") {
            call.mmsL1(Permission.CREATE_BRANCH) {
                pathParams {
                    org()
                    repo()
                    branch(legal=true)
                }

                val branchTriples = filterIncomingStatements("morb") {
                    branchNode().apply {
                        // assert ref/commit triples
                        normalizeRefOrCommit(this)

                        sanitizeCrudObject {
                            setProperty(RDF.type, MMS.Branch)
                            setProperty(MMS.id, branchId!!)
                            setProperty(MMS.etag, transactionId)
                            setProperty(MMS.createdBy, userNode())
                        }
                    }
                }

                log.info(branchTriples)

                val localConditions = DEFAULT_CONDITIONS.appendRefOrCommit()

                val updateString = buildSparqlUpdate {
                    insert {
                        txn(
                            "mms-txn:sourceGraph" to "?sourceGraph",
                        ) {
                            autoPolicy(Scope.BRANCH, Role.ADMIN_BRANCH)
                        }

                        graph("mor-graph:Metadata") {
                            raw("""
                                $branchTriples
                                
                                morb: mms:commit ?commitSource .
                            """)
                        }
                    }
                    where {
                        raw(*localConditions.requiredPatterns())

                        raw("""
                            optional {
                                graph mor-graph:Metadata {
                                    ?commitSource ^mms:commit/mms:snapshot ?snapshot .
                                    ?snapshot mms:graph ?sourceGraph .
                                }
                            }
                        """)
                    }
                }

                executeSparqlUpdate(updateString) {
                    iri(
                        if(refSource != null) "_refSource" to refSource!!
                        else "commitSource" to commitSource!!,
                    )
                }

                val constructString = buildSparqlQuery {
                    construct {
                        txn()

                        raw("""
                            morb: ?morb_p ?morb_o .
                        """)
                    }
                    where {
                        group {
                            raw("""
                                graph mor-graph:Metadata {
                                    morb: ?morb_p ?morb_o .
                                }
                            """)
                        }
                        raw("""
                            union ${localConditions.unionInspectPatterns()}    
                        """)
                    }
                }

                // create construct query to confirm transaction and fetch project details
                val constructResponseText = executeSparqlConstructOrDescribe(constructString)

                val constructModel = validateTransaction(constructResponseText, localConditions)

                // respond
                call.respondText(constructResponseText, RdfContentTypes.Turtle)

                val transactionNode = constructModel.createResource(prefixes["mt"])

                // clone graph
                run {
                    // snapshot is available for source commit
                    val sourceGraphs = transactionNode.listProperties(MMS.TXN.sourceGraph).toList()
                    if(sourceGraphs.size >= 1) {
                        // copy graph
                        executeSparqlUpdate("""
                            copy graph <${sourceGraphs[0].`object`.asResource().uri}> to mor-graph:Staging.${transactionId} ;
                            
                            insert {
                                morb: mms:snapshot mor-snapshot:Staging.${transactionId} . 
                                mor-snapshot:Staging.${transactionId} a mms:Staging ;
                                    mms:graph mor-graph:Staging.${transactionId} ;
                                    .
                            }
                        """)

                        // copy staging => model
                        executeSparqlUpdate("""
                            copy mor-snapshot:Staging.${transactionId} mor-snapshot:Model.${transactionId} ;
                            
                            insert {
                                morb: mms:snapshot mor-snapshot:Model.${transactionId} .
                                mor-snapshot:Model.${transactionId} a mms:Model ;
                                    mms:graph mor-graph:Model.${transactionId} ;
                                    .
                            }
                        """)
                    }
                    // no snapshots available, must build for commit
                    else {
                        TODO("build snapshot")
                    }
                }

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
                    log.info(dropResponseText)
                }
            }
        }
    }
}