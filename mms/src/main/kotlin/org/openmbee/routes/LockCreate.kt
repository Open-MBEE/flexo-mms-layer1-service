package org.openmbee.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.rdf.model.impl.ResourceImpl
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*


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


@OptIn(InternalAPI::class)
fun Application.createLock() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}/commit/{commitId}/locks/{lockId}") {
            call.crud {
                pathParams {
                    org()
                    repo()
                    commit()
                    lock(legal = true)
                }

                val lockTriples = filterIncomingStatements("morcl") {
                    lockNode().apply {
                        sanitizeCrudObject()

                        addProperty(RDF.type, MMS.Lock)
                        addProperty(MMS.id, lockId)
                        addProperty(MMS.commit, commitNode())
                        addProperty(MMS.createdBy, userNode())
                    }
                }

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