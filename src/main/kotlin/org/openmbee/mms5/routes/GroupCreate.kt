package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*
import org.openmbee.mms5.plugins.UserDetailsPrincipal


private val DEFAULT_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_GROUP, Scope.ACCESS_CONTROL)

    require("groupNotExists") {
        handler = { mms -> "The provided group <${mms.prefixes["mag"]}> already exists." }

        """
            # group must not yet exist
            graph m-graph:AccessControl.Agents {
                filter not exists {
                    mag: a mms:Group .
                }
            }
        """
    }
}

fun Route.createGroup() {
    put("/groups/{groupId}") {
        call.mmsL1(Permission.CREATE_GROUP) {
            pathParams {
                group(legal=true)
            }

            val grouopTriples = filterIncomingStatements("mag") {
                groupNode().apply {
                    sanitizeCrudObject {
                        setProperty(RDF.type, MMS.Group)
                        setProperty(MMS.id, groupId!!)
                        setProperty(MMS.etag, transactionId)
                    }
                }
            }

            val localConditions = DEFAULT_CONDITIONS.append {
                assertPreconditions(this) {
                    """
                        graph m-graph:AccessControl.Agents {
                            mag: mms:etag ?__mms_etag .
                            
                            $it
                        }
                    """
                }
            }

            val updateString = buildSparqlUpdate {
                insert {
                    txn {
                        autoPolicy(Scope.GROUP, Role.ADMIN_GROUP)
                    }

                    graph("m-graph:AccessControl.Agents") {
                        raw(grouopTriples)
                    }
                }
                where {
                    raw(*localConditions.requiredPatterns())
                }
            }

            executeSparqlUpdate(updateString)


            // create construct query to confirm transaction and fetch project details
            val constructString = buildSparqlQuery {
                construct {
                    txn()

                    raw("""
                        mag: ?mag_p ?mag_o .
                    """)
                }
                where {
                    group {
                        txn()

                        graph("m-graph:AccessControl.Agents") {
                            raw("""
                                mag: ?mag_p ?mag_o .
                            """)
                        }
                    }
                    raw("""union ${localConditions.unionInspectPatterns()}""")
                }
            }

            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            // log
            log.info("Triplestore responded with:\n$constructResponseText")

            val model = validateTransaction(constructResponseText, localConditions)

            checkPreconditions(model, prefixes["mag"])

            // respond
            call.respondText(constructResponseText, RdfContentTypes.Turtle)

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
