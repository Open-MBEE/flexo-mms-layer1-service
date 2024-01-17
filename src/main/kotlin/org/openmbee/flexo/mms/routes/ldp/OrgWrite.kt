package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse

// require that the given org does not exist before attempting to create it
private fun ConditionsBuilder.orgNotExists() {
    require("orgNotExists") {
        handler = { layer1 -> "The provided org <${layer1.prefixes["mo"]}> already exists." to HttpStatusCode.BadRequest }

        """
            # org must not yet exist
            filter not exists {
                graph m-graph:Cluster {
                    mo: a mms:Org .
                }
            }
        """
    }
}

// selects all properties of an existing org
private fun PatternBuilder<*>.existingOrg() {
    graph("m-graph:Cluster") {
        raw("""
            mo: ?orgExisting_p ?orgExisting_o .
        """)
    }
}

/**
 * Creates or replaces org(s)
 *
 * TResponseContext generic is bound by LdpWriteResponse, which can be a response to either a PUT or POST request
 */
suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createOrReplaceOrg() {
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

    // resolve ambiguity
    if(intentIsAmbiguous) {
        // ask if org exists
        val probeResults = executeSparqlSelectOrAsk(buildSparqlQuery {
            ask {
                existingOrg()
            }
        })

        // parse response
        val exists = parseSparqlResultsJsonAsk(probeResults)

        // org does not exist
        if(!exists) {
            replaceExisting = false
        }
    }

    // build conditions
    val localConditions = GLOBAL_CRUD_CONDITIONS.append {
        // POST
        if(isPostMethod) {
            // reject preconditions on POST; ETags not created for cluster since that would degrade multi-tenancy
            if(ifMatch != null || ifNoneMatch != null) {
                throw PreconditionsForbidden("when creating org via POST")
            }
        }
        // not POST
        else {
            // resource must exist
            if(mustExist) {
                orgExists()
            }

            // resource must not exist
            if(mustNotExist) {
                orgNotExists()
            }
            // resource may exist
            else {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph m-graph:Cluster {
                            ${if(mustExist) "" else "optional {"}
                                mo: mms:etag ?__mms_etag .
                                ${values.reindent(8)}
                            ${if(mustExist) "" else "}"}
                        }
                    """
                }
            }
        }

        // intent is ambiguous or resource is definitely being replaced
        if(replaceExisting) {
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
                existingOrg()
            }
        }
        insert {
            // create a new txn object in the transactions graph
            txn {
                // create a new policy that grants this user admin over the new org
                if(!replaceExisting) autoPolicy(Scope.ORG, Role.ADMIN_ORG)
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

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "mo", true)
}