package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse


// require that the given group does not exist before attempting to create it
private fun ConditionsBuilder.groupNotExists() {
    // require that the given group does not exist before attempting to create it
    require("groupNotExists") {
        handler = { mms -> "The provided group <${mms.prefixes["mg"]}> already exists." to HttpStatusCode.BadRequest }

        """
            # group must not yet exist
            graph m-graph:AccessControl.Agents {
                filter not exists {
                    mg: a mms:Group .
                }
            }
        """
    }
}

// selects all properties of an existing group
private fun PatternBuilder<*>.existingGroup() {
    graph("m-graph:AccessControl.Agents") {
        raw("""
            mg: ?groupExisting_p ?groupExisting_o .
        """)
    }
}

suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createOrReplaceGroup() {
    // process RDF body from user about this new group
    val groupTriples = filterIncomingStatements("mg") {
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

    // resolve ambiguity
    if(intentIsAmbiguous) {
        // ask if org exists
        val probeResults = executeSparqlSelectOrAsk(buildSparqlQuery {
            ask {
                existingGroup()
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
        // require that the user has the ability to create groups on an access-control-level scope
        permit(Permission.CREATE_GROUP, Scope.GROUP)

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
                groupExists()
            }

            // resource must not exist
            if(mustNotExist) {
                groupNotExists()
            }
            // resource may exist
            else {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph m-graph:AccessControl.Agents {
                            ${if(mustExist) "" else "optional {"}
                                mg: mms:etag ?__mms_etag .
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
                existingGroup()
            }
        }
        insert {
            // create a new txn object in the transactions graph
            txn {
                // create a new policy that grants this user admin over the new group
                if(!replaceExisting) autoPolicy(Scope.GROUP, Role.ADMIN_GROUP)
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
                mg: ?mg_p ?mg_o .
            """)
        }
        where {
            // first group in a series of unions fetches intended outputs
            group {
                txn(null, "mg")

                graph("m-graph:AccessControl.Agents") {
                    raw(""" 
                        mg: ?mg_p ?mg_o .
                    """)
                }
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "mg", !replaceExisting)
}
