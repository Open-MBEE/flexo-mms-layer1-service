package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse
import org.openmbee.flexo.mms.server.LdpPatchResponse


// require that the given lock does not exist (before attempting to create it)
private fun ConditionsBuilder.lockNotExists() {
    require("lockNotExists") {
        handler = { mms -> "The provided lock <${mms.prefixes["morl"]}> already exists." to HttpStatusCode.Conflict }

        """
            # lock must not yet exist
            filter not exists {
                graph mor-graph:Metadata {
                    morl: a mms:Lock .
                }
            }
        """
    }
}

// require that the given lock exists
private fun ConditionsBuilder.lockExists() {
    require("lockExists") {
        handler = { layer1 -> "Lock <${layer1.prefixes["morl"]}> does not exist." to
                if(null != layer1.ifMatch) HttpStatusCode.PreconditionFailed else HttpStatusCode.NotFound }

        """
            # lock must exist
            graph mor-graph:Metadata {
                morl: a mms:Lock ;
                    ?lockExisting_p ?lockExisting_o .
            }
        """
    }
}

// selects all properties of an existing lock
private fun PatternBuilder<*>.existingLock() {
    graph("mor-graph:Metadata") {
        raw("""
            morl: ?lockExisting_p ?lockExisting_o .
        """)
    }
}


private const val SPARQL_CONSTRUCT_SNAPSHOT = """
    construct {
        ?baseSnapshot mms:graph ?baseGraph .
        ?baseRef
            mms:commit ?baseCommit ;
            mms:snapshot ?baseSnapshot .

        ?ancestor mms:parent ?parent ;
            mms:patch ?patch ;
            mms:where ?where .
    } where {
        graph mor-graph:Metadata {
            # locate a commit that...
            ?__mms_commit mms:parent* ?baseCommit .

            # ... is targeted by some ref
            ?baseRef mms:commit ?baseCommit ;
                # access its snapshot
                mms:snapshot ?baseSnapshot .

            # and that snapshot's graph
            ?baseSnapshot mms:graph ?baseGraph .

            # only match the most recent snapshot in the commit history
            filter not exists {
                morc: mms:parent* ?newerCommit .
                ?newerCommit mms:parent* ?baseCommit ;
                    ^mms:commit/mms:snapshot ?newerSnapshot .

                filter(?newerCommit != ?baseCommit)
            }


            # fetch the ancestry between the base and target commits
            optional {
                ?__mms_commit mms:parent* ?ancestor .
                ?ancestor mms:parent ?parent ;
                    mms:data ?data .
                ?data mms:patch ?patch ;
                    mms:where ?where .
                
                # exclude commits older than the base commit
                filter not exists {
                    ?baseCommit mms:parent* ?ancestor .
                }
            }
        }
    }
"""

suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createOrReplaceLock() {
    // process RDF body from user about this new lock
    val lockTriples = filterIncomingStatements("morl") {
        // relative to this lock node
        lockNode().apply {
            // assert and normalize ref/commit triples
            normalizeRefOrCommit(this)

            // sanitize statements
            sanitizeCrudObject {
                setProperty(RDF.type, MMS.Lock)
                setProperty(MMS.id, lockId!!)
                setProperty(MMS.etag, transactionId, true)
                setProperty(MMS.createdBy, userNode(), true)
            }
        }
    }

    // resolve ambiguity
    if(intentIsAmbiguous) {
        // ask if repo exists
        val probeResults = executeSparqlSelectOrAsk(buildSparqlQuery {
            ask {
                existingLock()
            }
        })

        // parse response
        val exists = parseSparqlResultsJsonAsk(probeResults)

        // lock does not exist
        if(!exists) {
            replaceExisting = false
        }
    }

    // extend the default conditions with requirements for user-specified ref or commit
    val localConditions = REPO_CRUD_CONDITIONS.append {
        // POST
        if(isPostMethod) {
            // user is asking to create lock only if the state of its container repo passes their preconditions
            appendPreconditions { values ->
                """
                    graph mor-graph:Metadata {
                        morl: mms:etag ?__mms_etag .

                        $values
                    }
                """
            }
        }
        // not POST
        else {
            // resource must exist
            if(mustExist) {
                lockExists()
            }

            // resource must not exist
            if(mustNotExist) {
                lockNotExists()
            }
            // resource may exist
            else {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph mor-graph:Metadata {
                            ${if(mustExist) "" else "optional {"}
                                morl: mms:etag ?__mms_etag .
                                $values
                            ${if(mustExist) "" else "}"}
                        }
                    """
                }
            }
        }

        // resource is being replaced
        if(replaceExisting) {
            // require that the user has the ability to create locks on a repo-level scope
            permit(Permission.UPDATE_LOCK, Scope.REPO)
        }
        // resource is being created
        else {
            // require that the user has the ability to create locks on a repo-level scope
            permit(Permission.CREATE_LOCK, Scope.REPO)
        }
    }.appendRefOrCommit()

    // only support lock on existing ref for now
    if(refSource == null) {
        throw Http500Excpetion("Creating Locks on commits is not yet supported")
    }

    // prep SPARQL UPDATE string
    val updateString = buildSparqlUpdate {
        if(replaceExisting) {
            delete {
                existingLock()
            }
        }
        insert {
            // create a new txn object in the transactions graph
            txn {
                // not replacing existing; create new policy
                if(!replaceExisting) {
                    // create a new policy that grants this user admin over the new lock
                    autoPolicy(Scope.LOCK, Role.ADMIN_LOCK)
                }
            }

            // insert the triples about the new lock, including arbitrary metadata supplied by user
            graph("mor-graph:Metadata") {
                raw("""
                    $lockTriples
                    
                    morl: mms:commit ?__mms_commitSource ;
                        mms:snapshot ?__mms_commitSnapshot ;
                        .
                """)
            }
        }
        where {
            // assert the required conditions (e.g., access-control, existence, etc.)
            raw(*localConditions.requiredPatterns())

            graph("mor-graph:Metadata") {
                raw("""
                    ?__mms_commitLock a mms:Lock ;
                        mms:commit ?__mms_commitSource ;
                        mms:snapshot ?__mms_commitSnapshot ;
                        .
                """)
            }
        }
    }

    log.info(updateString)

    // execute update
    executeSparqlUpdate(updateString) {
        prefixes(prefixes)

        // replace IRI substitution variables
        iri(
            "_refSource" to refSource!!
        )
    }

    // create construct query to confirm transaction and fetch lock details
    val constructString = buildSparqlQuery {
        construct  {
            // all the details about this transaction
            txn()

            // all the properties about this lock
            raw("""
                morl: ?morl_p ?morl_o .
            """)
        }
        where {
            // first group in a series of unions fetches intended outputs
            group {
                txn(null, "morl")

                raw("""
                    graph mor-graph:Metadata {
                        morl: ?morl_p ?morl_o .
                    }
                """)
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

//    // check that the user-supplied HTTP preconditions were met
//    handleEtagAndPreconditions(constructModel, prefixes["morl"])

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "morl", !replaceExisting)
}
