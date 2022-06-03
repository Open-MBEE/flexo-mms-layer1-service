package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*

// default starting conditions for any calls to create an org
private val DEFAULT_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    // require that the user has the ability to create orgs on a cluster-level scope
    permit(Permission.CREATE_ORG, Scope.CLUSTER)

    // require that the given org does not exist before attempting to create it
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
            // parse the path params
            pathParams {
                org(legal=true)
            }

            // process RDF body from user about this new org
            val orgTriples = filterIncomingStatements("mo") {
                // relative to this org node
                orgNode().apply {
                    // sanitize statements
                    sanitizeCrudObject {
                        setProperty(RDF.type, MMS.Org)
                        setProperty(MMS.id, orgId!!)
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
                        // create a new policy that grants this user admin over the new org
                        autoPolicy(Scope.ORG, Role.ADMIN_ORG)
                    }

                    // insert the triples about the new org, including arbitrary metadata supplied by user
                    graph("m-graph:Cluster") {
                        raw(orgTriples)
                    }
                }
                where {
                    // assert the required conditions (e.g., access-control, existence, etc.)
                    raw(*localConditions.requiredPatterns())
                }
            }

            // execute update
            executeSparqlUpdate(updateString)

            // create construct query to confirm transaction and fetch org details
            val constructString = buildSparqlQuery {
                construct {
                    // all the details about this transaction
                    txn()

                    // all the properties about this org
                    raw("""
                        mo: ?mo_p ?mo_o .
                    """)
                }
                where {
                    // first group in a series of unions fetches intended outputs
                    group {
                        txn()

                        graph("m-graph:Cluster") {
                            raw("""
                                mo: ?mo_p ?mo_o .
                            """)
                        }
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
            val model = validateTransaction(constructResponseText, localConditions)

            // check that the user-supplied HTTP preconditions were met
            handleEtagAndPreconditions(model, prefixes["mo"])

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