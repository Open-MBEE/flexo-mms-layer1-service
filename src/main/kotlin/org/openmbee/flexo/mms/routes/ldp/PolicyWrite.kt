package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse


// require that the given policy does not exist before attempting to create it
private fun ConditionsBuilder.policyNotExists() {
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

// selects all properties of an existing policy
private fun PatternBuilder<*>.existingPolicy() {
    graph("m-graph:AccessControl.Policies") {
        raw("""
            mp: ?policyExisting_p ?policyExisting_o .
        """)
    }
}

suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createOrReplacePolicy() {
    // parse the path params
    parsePathParams {
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

    // resolve ambiguity
    if(intentIsAmbiguous) {
        // ask if policy exists
        val probeResults = executeSparqlSelectOrAsk(buildSparqlQuery {
            ask {
                existingPolicy()
            }
        })

        // parse response
        val exists = parseSparqlResultsJsonAsk(probeResults)

        // policy does not exist
        if(!exists) {
            replaceExisting = false
        }
    }

    // inherit the default conditions
    val localConditions = GLOBAL_CRUD_CONDITIONS.append {
        // POST
        if (isPostMethod) {
            // reject preconditions on POST; ETags not created for cluster since that would degrade multi-tenancy
            if (ifMatch != null || ifNoneMatch != null) {
                throw PreconditionsForbidden("when creating policy via POST")
            }
        }
        // not POST
        else {
            // resource must exist
            if (mustExist) {
                policyExists()
            }

            // resource must not exist
            if (mustNotExist) {
                policyNotExists()
            }
            // resource may exist
            else {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph m-graph:AccessControl.Policies {
                            ${if (mustExist) "" else "optional {"}
                                mp: mms:etag ?__mms_etag .
                                ${values.reindent(8)}
                            ${if (mustExist) "" else "}"}
                        }
                    """
                }
            }
        }

        // intent is ambiguous or resource is definitely being replaced
        if(replaceExisting) {
            // require that the user has the ability to update orgs on a cluster-level scope (necessarily implies ability to create)
            permit(Permission.UPDATE_POLICY, Scope.ACCESS_CONTROL_ANY)
        }
        // resource is being created
        else {
            // require that the user has the ability to create orgs on a cluster-level scope
            permit(Permission.CREATE_POLICY, Scope.ACCESS_CONTROL_ANY)
        }
    }

    // prep SPARQL UPDATE string
    val updateString = buildSparqlUpdate {
        if(replaceExisting) {
            delete {
                existingPolicy()
            }
        }
        insert {
            // create a new txn object in the transactions graph
            txn {
                // create a new policy that grants this user admin over the new policy
                if(!replaceExisting) autoPolicy(Scope.POLICY, Role.ADMIN_POLICY)
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

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "mp", !replaceExisting)
}
