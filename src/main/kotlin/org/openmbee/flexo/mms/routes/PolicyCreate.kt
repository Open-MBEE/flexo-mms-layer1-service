package org.openmbee.flexo.mms.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*


// default starting conditions for any calls to create a policy
private val DEFAULT_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    // require that the user has the ability to create policies on an access-control-level scope
    permit(Permission.CREATE_POLICY, Scope.POLICY)

    // require that the given policy does not exist before attempting to create it
    require("policyNotExists") {
        handler = { mms -> "The provided policy <${mms.prefixes["mp"]}> already exists." to HttpStatusCode.Conflict }

        """
            # destination policy must not yet exist
            graph m-graph:AccessControl.Policies {
                filter not exists {
                    mp: a mms:Policy .
                }
            }
        """
    }
}

fun Route.createPolicy() {
    put("/policies/{policyId}") {
        call.mmsL1(Permission.CREATE_POLICY) {
            // parse the path params
            pathParams {
                policy(legal=true)
            }

            // process RDF body from user about this new policy
            val policyTriples = filterIncomingStatements("mp") {
                // relative to this policy node
                policyNode().apply {
                    // expect exactly 1 subject node
                    val subjectNode = extractExactly1Uri(MMS.subject)

                    // expect exactly 1 scope node
                    val scopeNode = extractExactly1Uri(MMS.scope)

                    // expect 1 or more roles
                    val roleNodes = extract1OrMoreUris(MMS.role)

                    // sanitize statements
                    sanitizeCrudObject {
                        setProperty(RDF.type, MMS.Policy)
                        setProperty(MMS.id, policyId!!)
                        setProperty(MMS.etag, transactionId)
                        bypass(MMS.subject)
                        bypass(MMS.scope)
                        bypass(MMS.role)
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
                        // // create a new policy that grants this user admin over the new policy
                        // autoPolicy(Scope.POLICY, Role.ADMIN_POLICY)
                    }

                    // insert the triples about the new policy, including arbitrary metadata supplied by user
                    graph("m-graph:AccessControl.Policies") {
                        raw(policyTriples)
                    }
                }
                where {
                    // assert the required conditions (e.g., access-control, existence, etc.)
                    raw(*localConditions.requiredPatterns())
                }
            }

            // execute update
            executeSparqlUpdate(updateString)

            // create construct query to confirm transaction and fetch policy details
            val constructString = buildSparqlQuery {
                construct {
                    // all the details about this transaction
                    txn()

                    // all the properties about this policy
                    raw("""
                        mp: ?mp_p ?mp_o .
                    """)
                }
                where {
                    // first group in a series of unions fetches intended outputs
                    group {
                        txn(null, "mp")

                        raw("""
                            graph m-graph:AccessControl.Policies {
                                mp: ?mp_p ?mp_o .
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
            val model = validateTransaction(constructResponseText, localConditions, null, "mp")

            // check that the user-supplied HTTP preconditions were met
            handleEtagAndPreconditions(model, prefixes["mp"])

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
