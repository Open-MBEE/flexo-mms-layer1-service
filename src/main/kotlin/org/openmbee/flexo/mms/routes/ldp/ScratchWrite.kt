package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse


// require that the given scratch does not exist before attempting to create it
private fun ConditionsBuilder.scratchNotExists() {
    require("scratchNotExists") {
        handler = { layer1 -> "The provided scratch <${layer1.prefixes["mors"]}> already exists." to HttpStatusCode.BadRequest }

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
private fun PatternBuilder<*>.existingScratch() {
    graph("mor-graph:Metadata") {
        raw("""
            mors: ?scratchExisting_p ?scratchExisting_o .
        """)
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
                // add etag as txn id and org as orgNode()? I think I need this since it keeps saying I don't have an etag
                setProperty(MMS.etag, transactionId)
                setProperty(MMS.org, orgNode())
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


    // build conditions
    val localConditions = GLOBAL_CRUD_CONDITIONS.append {
        if(isPostMethod) {
            // reject preconditions
            if(ifMatch != null || ifNoneMatch != null) {
                throw PreconditionsForbidden("when creating scratch via POST")
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
//                // enforce preconditions if present
//                appendPreconditions { values ->
//                    """
//                        graph mor-graph:Metadata {
//                            ${if(mustExist) "" else "optional {"}
//                                mors: mms:id ?__mms_id .
//                                ${values.reindent(8)}
//                            ${if(mustExist) "" else "}"}
//                        }
//                    """
//                }
            }
        }

        // intent is ambiguous or resource is definitely being replaced
        if(replaceExisting) {
            // require that the user has the ability to update scratches on a repo-level scope (necessarily implies ability to create)
            permit(Permission.UPDATE_SCRATCH, Scope.REPO)
        }
        // resource is being created
        else {
            // require that the user has the ability to create scratches on a repo-level scope
            permit(Permission.CREATE_SCRATCH, Scope.REPO)
        }
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
                if(!replaceExisting) autoPolicy(Scope.SCRATCH, Role.ADMIN_SCRATCH)
            }

            // insert the triples about the scratch, including arbitrary metadata supplied by user
            graph("mor-graph:Metadata") {
                raw(scratchTriples)
            }
        }
        where {
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
            // first group in a series of unions fetches intended outputs
            group {
                txn(null, "mors")

                graph("mor-graph:Metadata") {
                    raw("""
                        mors: ?mors_p ?mors_o .
                    """)
                }
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "mors", !replaceExisting)
}
