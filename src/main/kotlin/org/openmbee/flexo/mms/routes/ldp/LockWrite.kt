package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse
import org.openmbee.flexo.mms.server.LdpPatchResponse
import java.time.Instant
import java.util.UUID


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
            // require that the user has the ability to update locks on a repo-level scope
            permit(Permission.UPDATE_LOCK, Scope.REPO)
        }
        // resource is being created
        else {
            // require that the user has the ability to create locks on a repo-level scope
            permit(Permission.CREATE_LOCK, Scope.REPO)
        }
    }.appendRefOrCommit()

    // --- Phase 1: Insert only the transaction (with conditions check), NOT lock metadata or auto policy ---
    val updateString = buildSparqlUpdate {
        insert {
            // create a new txn object in the transactions graph
            // store the commit source as an intermediate txn property
            txn(
                "mms-txn:commitSource" to "?__mms_commitSource",
            )
        }
        where {
            // assert the required conditions (e.g., access-control, existence, etc.)
            raw(*localConditions.requiredPatterns())

            if(refSource != null) {
                // ref-source: find commit through the ref's existing lock
                graph("mor-graph:Metadata") {
                    raw("""
                        ?__mms_commitLock a mms:Lock ;
                            mms:commit ?__mms_commitSource ;
                            .
                    """)
                }
            }
        }
    }

    log.info(updateString)

    // execute update
    executeSparqlUpdate(updateString) {
        prefixes(prefixes)

        // replace IRI substitution variables
        iri(
            // user specified either ref or commit
            if(refSource != null) "_refSource" to refSource!!
            else "__mms_commitSource" to commitSource!!,
        )
    }

    // --- Phase 2: Construct + validate transaction ---
    val constructString = buildSparqlQuery {
        construct {
            // all the details about this transaction
            txn()
        }
        where {
            // first group in a series of unions fetches intended outputs
            group {
                txn()
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // parameterizer setup shared by construct and later queries
    val sparqlSetup: SparqlParameterizer.() -> Unit = {
        prefixes(prefixes)

        // replace IRI substitution variables
        iri(
            // user specified either ref or commit
            if(refSource != null) "_refSource" to refSource!!
            else "__mms_commitSource" to commitSource!!,
        )
    }

    // execute construct to validate transaction
    val constructResponseText = executeSparqlConstructOrDescribe(constructString, sparqlSetup)

    // validate whether the transaction succeeded
    val constructModel = validateTransaction(constructResponseText, localConditions, null, null)

    // --- Phase 3: Resolve commit source, materialize model graph ---
    val resolvedCommitSource = commitSource ?: run {
        val transactionNode = constructModel.createResource(prefixes["mt"])
        transactionNode.listProperties(MMS.TXN.commitSource).toList().firstOrNull()
            ?.`object`?.asResource()?.uri
            ?: throw Http500Excpetion("Transaction missing commit source")
    }

    val modelGraph = "${prefixes["mor-graph"]}Model.${transactionId}"
    val materialized = materializeModelGraph(resolvedCommitSource, modelGraph)

    // --- Phase 4: Insert lock metadata + auto policy last (after graph setup) ---
    val autoPolicyCurie = if(!replaceExisting) "m-policy:AutoLockOwner.${UUID.randomUUID()}" else null
    executeSparqlUpdate(
        """
        ${if(replaceExisting) """
        delete {
            graph mor-graph:Metadata {
                morl: ?morl_old_p ?morl_old_o .
            }
        }
        """ else ""}
        insert {
            graph mor-graph:Metadata {
                $lockTriples
                
                morl: mms:commit ?_commitSource ;
                    mms:snapshot ?_commitSnapshot ;
                    mms:created ?_now ;
                    .
            }
            ${if(autoPolicyCurie != null) """
            graph m-graph:Transactions {
                mt: mms:createdPolicy $autoPolicyCurie .
            }
            
            graph m-graph:AccessControl.Policies {
                $autoPolicyCurie a mms:Policy ;
                    mms:subject mu: ;
                    mms:scope morl: ;
                    mms:role mms-object:Role.AdminLock ;
                    .
            }
            """ else ""}
        }
        where {
            ${if(replaceExisting) """
            # match existing lock triples for atomic delete+insert
            graph mor-graph:Metadata {
                morl: ?morl_old_p ?morl_old_o .
            }
            """ else """
            # re-check that the lock does not yet exist (atomicity guard)
            filter not exists {
                graph mor-graph:Metadata {
                    morl: a mms:Lock .
                }
            }
            """}
        }
    """
    ) {
        prefixes(prefixes)
        iri(
            "_commitSource" to resolvedCommitSource,
            "_commitSnapshot" to materialized.snapshotIri,
        )
        datatyped(
            "_now" to (Instant.now().toString() to XSDDatatype.XSDdateTime),
        )
    }

    // --- Phase 5: Construct + validate lock triples for response ---
    val lockConstructString = buildSparqlQuery {
        construct {
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
                txn()

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

    val lockConstructResponseText = executeSparqlConstructOrDescribe(lockConstructString, sparqlSetup)
    val lockModel = validateTransaction(lockConstructResponseText, localConditions, null, "morl")

    // set response ETag from the created/replaced lock
    handleWrittenResourceEtag(lockModel, prefixes["morl"]!!)

    // respond with the resource
    if(!replaceExisting) {
        responseContext.createdResource(prefixes["morl"]!!, lockModel)
    } else {
        responseContext.mutatedResource(prefixes["morl"]!!, lockModel)
    }

    // --- Phase 6: Clean up transaction ---
    run {
        val dropResponseText = executeSparqlUpdate("""
            delete where {
                graph m-graph:Transactions {
                    mt: ?p ?o .
                }
            }
        """)

        log.info("Delete transaction response:\n$dropResponseText")
    }
}
