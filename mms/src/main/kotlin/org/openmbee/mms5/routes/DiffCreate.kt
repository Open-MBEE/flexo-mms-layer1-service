package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*
import java.security.MessageDigest


private val DEFAULT_CONDITIONS = COMMIT_CRUD_CONDITIONS.append {
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

private fun hashString(input: String, algorithm: String): String {
    return MessageDigest
        .getInstance(algorithm)
        .digest(input.toByteArray())
        .fold("", { str, it -> str + "%02x".format(it) })
}


fun Application.createDiff() {
    routing {
        post("/orgs/{orgId}/repos/{repoId}/locks/{lockId}/diff") {
            call.mmsL1(Permission.CREATE_DIFF) {
                pathParams {
                    org()
                    repo()
                    commit()
                    lock()
                }

                diffId = transactionId

                val diffTriples = filterIncomingStatements("morb") {
                    diffNode().apply {
                        normalizeRefOrCommit(this)

                        sanitizeCrudObject {
                            setProperty(RDF.type, MMS.Diff)
                            setProperty(MMS.diffSrc, lockNode())
                            setProperty(MMS.createdBy, userNode())
                        }
                    }
                }

                val localConditions = DEFAULT_CONDITIONS.appendRefOrCommit()

                // generate sparql update
                val updateString = buildSparqlUpdate {
                    insert {
                        txn(
                            "mms-txn:diff" to "?diff",
                            "mms-txn:commitSource" to "?commitSource",
                            "mms-txn:diffInsGraph" to "?diffInsGraph",
                            "mms-txn:diffDelGraph" to "?diffDelGraph",
                        ) {
                            autoPolicy(Scope.DIFF, Role.ADMIN_DIFF)
                        }

                        raw("""
                            graph ?diffInsGraph {
                                ?ins_s ?ins_p ?ins_o .    
                            }
                            
                            graph ?diffDelGraph {
                                ?del_s ?del_p ?del_o .
                            }
                            
                            graph mor-graph:Metadata {
                                $diffTriples
                                
                                ?diff mms:id ?diffId ;
                                    mms:diffSrc ?commitSource ;
                                    mms:diffDst morc: ;
                                    mms:insGraph ?diffInsGraph ;
                                    mms:delGraph ?diffDelGraph ;
                                    .
                            }
                        """)
                    }
                    where {
                        raw(*localConditions.requiredPatterns())

                        raw("""
                            graph ?srcGraph {
                                ?src_s ?src_p ?src_o .    
                            }
                            
                            graph ?dstGraph {
                                ?dst_s ?dst_p ?dst_o .
                            }
                            
                            graph ?srcGraph {
                                ?ins_s ?ins_p ?ins_o .
                                
                                minus {
                                    ?dst_s ?dst_p ?dst_o .
                                }
                            }
                            
                            graph ?dstGraph {
                                ?del_s ?del_p ?del_o .
                                
                                minus {
                                    ?src_s ?src_p ?src_o .
                                }
                            }
                            
                            optional {
                                graph mor-graph:Metadata {
                                    ?commitSource ^mms:commit/mms:snapshot ?snapshot .
                                    ?snapshot mms:graph ?sourceGraph  .
                                }
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
                        """)
                    }
                }

                executeSparqlUpdate(updateString) {
                    iri(
                        if(refSource != null) "refSource" to refSource!!
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
                            txn()

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

                val constructResponseText = executeSparqlConstructOrDescribe(constructString)

                val constructModel = validateTransaction(constructResponseText, localConditions)

                call.respondText(constructResponseText, RdfContentTypes.Turtle)

                // clone graph
                run {
                    val transactionNode = constructModel.createResource(prefixes["mt"])

                    // snapshot is available for source commit
                    val sourceGraphs = transactionNode.listProperties(MMS.TXN.sourceGraph).toList()
                    if(sourceGraphs.size >= 1) {
                        // copy graph
                        executeSparqlUpdate("""
                            copy graph <${sourceGraphs[0].`object`.asResource().uri}> to mor-graph:Staging.${transactionId} ;
                            
                            insert {
                                morb: mms:snapshot ?snapshot .
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
                    """) {
                        prefixes(prefixes)
                    }

                    // log response
                    log.info(dropResponseText)
                }
            }
        }
    }
}