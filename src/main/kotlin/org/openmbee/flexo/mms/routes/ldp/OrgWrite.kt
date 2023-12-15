package org.openmbee.flexo.mms.routes.ldp

import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpWriteResponse

// default starting conditions for any calls to create an org
private val ORG_CREATE_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    // require that the user has the ability to create orgs on a cluster-level scope
    permit(Permission.CREATE_ORG, Scope.CLUSTER)
}


/**
 * Creates or replaces org(s)
 *
 * TResponseContext generic is bound by LdpWriteResponse, which can be a response to either a PUT or POST request
 */
suspend fun <TResponseContext: LdpWriteResponse> LdpDcLayer1Context<TResponseContext>.createOrReplaceOrg() {
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

    // build conditions
    val localConditions = GLOBAL_CRUD_CONDITIONS.append {
        // resource must exist
        if(mustExist) {
            orgExists()

            // additionally, it's etag must match satisfy the given precondition
            val preconditions = injectPreconditions()
            if(preconditions.trim().isNotEmpty()) {
                resourceMatchesEtag(prefixes["mo"]!!, preconditions, "org")
            }
        }
        // resource must not exist
        else if(mustNotExist) {
            orgNotExists()
        }

        // intent is ambiguous or resource is definitely being replaced
        if(intentIsAmbiguous || replaceExisting) {
            // require that the user has the ability to update orgs on a cluster-level scope (necessarily implies ability to create)
            permit(Permission.UPDATE_ORG, Scope.CLUSTER)
        }
        // resource is being created
        else {
            // require that the user has the ability to create orgs on a cluster-level scope
            permit(Permission.CREATE_ORG, Scope.CLUSTER)
        }
    }

    // prep SPARQL UPDATE string
    val updateString = buildSparqlUpdate {
        if(replaceExisting) {
            delete {
                graph("m-graph:Cluster") {
                    raw("""
                        mo: ?orgExisting_p ?orgExisting_o .
                    """)
                }
            }
        }
        insert {
            // create a new txn object in the transactions graph
            txn {
                // create a new policy that grants this user admin over the (potentially) new org
                if(!replaceExisting || intentIsAmbiguous) autoPolicy(Scope.ORG, Role.ADMIN_ORG)
            }

            // insert the triples about the org, including arbitrary metadata supplied by user
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
                txn(null, "mo")

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
    val model = validateTransaction(constructResponseText, localConditions, null, "mo")

    // set response ETag from created/replaced resource
    handleWrittenResourceEtag(model, prefixes["mo"]!!)

    // respond with the created resource
    responseContext.createdResource(prefixes["mo"]!!, model)

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