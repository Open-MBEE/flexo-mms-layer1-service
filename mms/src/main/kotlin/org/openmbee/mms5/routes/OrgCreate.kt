package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*


private val DEFAULT_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_ORG, Scope.CLUSTER)

    require("orgNotExists") {
        handler = { mms -> "The provided org <${mms.prefixes["mo"]}> already exists." }

        """
            # org must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    mo: a mms:Org .
                }
            }
        """
    }
}

fun Route.createOrg() {
    put("/orgs/{orgId}") {
        call.mmsL1(Permission.CREATE_ORG) {
            pathParams {
                org(legal = true)
            }

            val orgTriples = filterIncomingStatements("mo") {
                orgNode().apply {
                    sanitizeCrudObject {
                        setProperty(RDF.type, MMS.Org)
                        setProperty(MMS.id, orgId!!)
                        setProperty(MMS.etag, transactionId)
                    }
                }
            }

            val localConditions = DEFAULT_CONDITIONS.append {
                assertPreconditions(this) {
                    """
                            graph m-graph:Cluster {
                                mo: mms:etag ?etag .
                                
                                $it
                            }
                        """
                }
            }

            val updateString = buildSparqlUpdate {
                insert {
                    txn {
                        autoPolicy(Scope.ORG, Role.ADMIN_ORG)
                    }

                    graph("m-graph:Cluster") {
                        raw(orgTriples)
                    }
                }
                where {
                    raw(*localConditions.requiredPatterns())
                    groupDns()
                }
            }

            executeSparqlUpdate(updateString)


            // create construct query to confirm transaction and fetch project details
            val constructString = buildSparqlQuery {
                construct {
                    txn()

                    raw(
                        """
                            mo: ?mo_p ?mo_o .
                        """
                    )
                }
                where {
                    group {
                        txn()

                        graph("m-graph:Cluster") {
                            raw(
                                """
                                    mo: ?mo_p ?mo_o .
                                """
                            )
                        }
                    }
                    raw("""union ${localConditions.unionInspectPatterns()}""")
                    groupDns()
                }
            }

            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            // log
            log.info("Triplestore responded with:\n$constructResponseText")

            val model = validateTransaction(constructResponseText, localConditions)

            checkPreconditions(model, prefixes["mo"]!!)

            // respond
            call.respondText(constructResponseText, RdfContentTypes.Turtle)

            // delete transaction
            run {
                // submit update
                val dropResponseText = executeSparqlUpdate(
                    """
                        delete where {
                            graph m-graph:Transactions {
                                mt: ?p ?o .
                            }
                        }
                    """
                )

                // log response
                log.info(dropResponseText)
            }
        }
    }
}
