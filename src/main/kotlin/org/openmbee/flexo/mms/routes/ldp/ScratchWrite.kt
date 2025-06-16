package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.GspRequest
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse


// require that the given scratch does not exist before attempting to create it
private fun ConditionsBuilder.scratchNotExists() {
    require("scratchNotExists") {
        handler = { layer1 -> "The provided scratch <${layer1.prefixes["mors"]}> already exists." to HttpStatusCode.Conflict }

        """
            # scratch must not yet exist
            filter not exists {
                graph mor-graph:Metadata {
                    mors: a mms:Scratch .
                }
            }
        """
    }
}

// selects all properties of an existing scratch
private fun PatternBuilder<*>.existingScratch(filterCreate: Boolean = false) {
    graph("mor-graph:Metadata") {
        raw("""
            mors: ?scratchExisting_p ?scratchExisting_o .
        """)
        if (filterCreate) {
            raw("""
                filter(?scratchExisting_p != mms:created)
                filter(?scratchExisting_p != mms:createdBy)
            """.trimIndent())
        }
    }
}

/**
 * Creates or replaces scratch(s)
 *
 * TResponseContext generic is bound by LdpWriteResponse, which can be a response to either a PUT or POST request
 */
suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createOrReplaceScratch() {
    // process RDF body from user about this new scratch
    val scratchTriples = filterIncomingStatements("mors") {
        // relative to this scratch node
        scratchNode().apply {
            // sanitize statements
            sanitizeCrudObject {
                setProperty(RDF.type, MMS.Scratch)
                setProperty(MMS.id, scratchId!!)
                setProperty(MMS.etag, transactionId, true)
            }
        }
    }

    // resolve ambiguity
    if(intentIsAmbiguous) {
        // ask if scratch exists
        val probeResults = executeSparqlSelectOrAsk(buildSparqlQuery {
            ask {
                existingScratch()
            }
        })

        // parse response
        val exists = parseSparqlResultsJsonAsk(probeResults)

        // scratch does not exist
        if(!exists) {
            replaceExisting = false
        }
    }
    // resource is being replaced
    val permission = if(replaceExisting) {
        // require that the user has the ability to update scratches on a scratch-level scope (necessarily implies ability to create)
        Permission.UPDATE_SCRATCH
    }
    // resource is being created
    else {
        // require that the user has the ability to create scratches on an org-level scope
        Permission.CREATE_SCRATCH
    }

    // build conditions
    val localConditions = REPO_CRUD_CONDITIONS.append {
        if(isPostMethod) {
            // reject preconditions
            appendPreconditions { values ->
                """
                    graph m-graph:Cluster {
                        mor: mms:etag ?__mms_etag .

                        $values
                    }
                """
            }
        }
        // not POST
        else {
            // resource must exist
            if(mustExist) {
                scratchExists()
            }

            // resource must not exist
            if(mustNotExist) {
                scratchNotExists()
            }
            // resource may exist
            else {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph mor-graph:Metadata {
                            ${if(mustExist) "" else "optional {"}
                                mors: mms:etag ?__mms_etag .
                                $values
                            ${if(mustExist) "" else "}"}
                        }
                    """
                }
            }
        }

        permit(permission, Scope.REPO)
    }

    // prep SPARQL UPDATE string
    val updateString = buildSparqlUpdate {
        if(replaceExisting) {
            delete {
                existingScratch()
            }
        }
        insert {
            // create a new txn object in the transactions graph
            txn {
                // create a new policy that grants this user admin over the new scratch
                if (!replaceExisting) {
                    autoPolicy(Scope.SCRATCH, Role.ADMIN_SCRATCH)
                }
                replacesExisting(replaceExisting)
            }

            // insert the triples about the scratch, including arbitrary metadata supplied by user
            graph("mor-graph:Metadata") {
                raw(scratchTriples)
                if (!replaceExisting) {
                    raw("""
                        mors: mms:created ?_now ;
                            mms:createdBy mu: .
                    """
                    )
                }
            }
        }
        where {
            if (replaceExisting) {
                existingScratch(true)
            }
            // assert the required conditions (e.g., access-control, existence, etc.)
            raw(*localConditions.requiredPatterns())
        }
    }

    // execute update
    executeSparqlUpdate(updateString)

    // create construct query to confirm transaction and fetch scratch details
    val constructString = buildSparqlQuery {
        construct {
            // all the details about this transaction
            txn()

            // all the properties about this scratch
            raw("""
                mors: ?mors_p ?mors_o .
            """)
        }
        where {
            txnOrInspections(null, localConditions) {
                raw("""
                    # extract the created/updated repo properties
                    graph mor-graph:Metadata {
                        mors: ?mors_p ?mors_o .
                    }
                """)
            }
        }
    }

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "mors", !replaceExisting)
}

/**
 * Deletes the scratch graph
 */
suspend fun Layer1Context<GspRequest, *>.deleteScratch() {
    // auth check
    checkModelQueryConditions(targetGraphIri = "${prefixes["mor-graph"]}Scratch.$scratchId", conditions = SCRATCH_QUERY_CONDITIONS.append {
        assertPreconditions(this)
    })

    // delete the graph
    deleteGraph("${prefixes["mor-graph"]}Scratch.$scratchId") {
        // permissions check
        graph("mor-graph:Metadata") {
            auth(Permission.DELETE_SCRATCH.scope.id, SCRATCH_DELETE_CONDITIONS)
        }
    }

    // close response
    call.respondText("", status = HttpStatusCode.NoContent)
}
