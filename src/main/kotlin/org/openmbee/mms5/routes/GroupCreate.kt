package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*


// default starting conditions for any calls to create a group
private val DEFAULT_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    // require that the user has the ability to create groups on an access-control-level scope
    permit(Permission.CREATE_GROUP, Scope.ACCESS_CONTROL)

    // require that the given group does not exist before attempting to create it
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
            // parse the path params
            pathParams {
                group(legal=true)
            }

            // process RDF body from user about this new group
            val groupTriples = filterIncomingStatements("mag") {
                // relative to this group node
                groupNode().apply {
                    // sanitize statements
                    sanitizeCrudObject {
                        setProperty(RDF.type, MMS.Group)
                        setProperty(MMS.id, groupId!!)
                        setProperty(MMS.etag, transactionId)
                    }
                }
            }

            // inherit the default conditions
            val localConditions = DEFAULT_CONDITIONS

            // prep SPARQL UPDATE string
            val updateString = buildSparqlUpdate {
                insert {
                    // create a new txn object in the transactions graph
                    txn {
                        // create a new policy that grants this user admin over the new branch
                        autoPolicy(Scope.GROUP, Role.ADMIN_GROUP)
                    }

                    // insert the triples about the new group, including arbitrary metadata supplied by user
                    graph("m-graph:AccessControl.Agents") {
                        raw(groupTriples)
                    }
                }
                where {
                    // assert the required conditions (e.g., access-control, existence, etc.)
                    raw(*localConditions.requiredPatterns())
                }
            }

            // execute update
            executeSparqlUpdate(updateString)

            // create construct query to confirm transaction and fetch group details
            val constructString = buildSparqlQuery {
                construct {
                    // all the details about this transaction
                    txn()

                    // all the properties about this group
                    raw("""
                        mag: ?mag_p ?mag_o .
                    """)
                }
                where {
                    // first group in a series of unions fetches intended outputs
                    group {
                        txn(null, "mag")

                        raw("""
                            graph m-graph:AccessControl.Agents {
                                mag: ?mag_p ?mag_o .
                            }
                        """)
                    }
                    // all subsequent unions are for inspecting what if any conditions failed
                    raw("""union ${localConditions.unionInspectPatterns()}""")
                }
            }

            // execute construct
            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            // log
            log.info("Triplestore responded with:\n$constructResponseText")

            // validate whether the transaction succeeded
            val model = validateTransaction(constructResponseText, localConditions, null, "mag")

            // check that the user-supplied HTTP preconditions were met
            handleEtagAndPreconditions(model, prefixes["mag"])

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
