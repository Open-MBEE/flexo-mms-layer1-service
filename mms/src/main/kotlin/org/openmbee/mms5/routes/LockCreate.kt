package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*


private val DEFAULT_CONDITIONS = COMMIT_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_LOCK, Scope.REPO)

    require("lockNotExists") {
        handler = { prefixes -> "The provided lock <${prefixes["morcl"]}> already exists." }

        """
            # lock must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    morcl: a mms:Lock .
                }
            }
        """
    }
}


fun Application.createLock() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}/commit/{commitId}/locks/{lockId}") {
            call.mmsL1(Permission.CREATE_LOCK) {
                pathParams {
                    org()
                    repo()
                    commit()
                    lock(legal = true)
                }

                val lockTriples = filterIncomingStatements("morcl") {
                    lockNode().apply {
                        sanitizeCrudObject {
                            addProperty(RDF.type, MMS.Lock)
                            addProperty(MMS.id, lockId)
                            addProperty(MMS.commit, commitNode())
                            addProperty(MMS.createdBy, userNode())
                        }
                    }
                }

                // locate base snapshot
                val constructModel = executeSparqlConstructOrDescribe("""
                    construct {
                        ?commit mms:parent ?parent .
                    
                        ?snapshot mms:ref ?ref ;
                            mms:graph ?graph .
                    
                        ?ref mms:commit ?commit .
                    } where {
                        {
                            graph m-graph:Schema {
                                ?snapshotClass rdfs:subClassOf* mms:Snapshot .
                            }
                    
                            graph mor-graph:Metadata {
                                morc: mms:parent* ?commit .
                    
                                ?commit mms:parent ?parent .
                    
                                ?snapshot a ?snapshotClass ;
                                    mms:ref ?ref ;
                                    mms:graph ?graph .
                    
                                ?ref mms:commit ?commit .
                            }
                    
                            filter not exists {
                                graph m-graph:Schema {
                                    ?newerSnapshotClass rdfs:subClassOf* mms:Snapshot .
                                }
                    
                                graph mor-graph:Metadata {
                                    morc: mms:parent* ?newerCommit .
                    
                                    ?newerCommit mms:parent* ?commit .
                    
                                    ?newerSnapshot a ?newerSnapshotClass ;
                                        mms:ref/mms:commit ?newerCommit .
                                }
                            }
                        }
                    }
                """)

                val updateString = buildSparqlUpdate {
                    insert {
                        txn {
                            autoPolicy(Scope.LOCK, Role.ADMIN_LOCK)
                        }

                        graph("mor-graph:Metadata") {
                            raw(lockTriples)
                        }
                    }
                    where {
                        raw(*DEFAULT_CONDITIONS.requiredPatterns())
                    }

                    raw("""
                        ; copy 
                    """)
                }

                executeSparqlUpdate(updateString)

                val localConditions = DEFAULT_CONDITIONS

                // create construct query to confirm transaction and fetch project details
                val constructString = buildSparqlQuery {
                    construct  {
                        txn()

                        raw("""
                            morcl: ?morcl_p ?morcl_o .
                        """)
                    }
                    where {
                        group {
                            raw("""
                                graph mor-graph:Metadata {
                                    morcl: ?morcl_p ?morcl_o .
                                }
                            """)
                        }
                        raw("""union ${localConditions.unionInspectPatterns()}""")
                    }
                }

                val constructResponseText = executeSparqlConstructOrDescribe(constructString)

                validateTransaction(constructResponseText, localConditions)

                call.respondText(constructResponseText, RdfContentTypes.Turtle)

                // log
                log.info("Triplestore responded with:\n$constructResponseText")

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