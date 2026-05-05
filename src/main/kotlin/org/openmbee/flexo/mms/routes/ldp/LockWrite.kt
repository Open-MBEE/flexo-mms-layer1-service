package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse
import org.openmbee.flexo.mms.routes.sparql.*
import java.time.Instant
import java.util.UUID

// default starting conditions for any calls to create a lock
private val DEFAULT_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    // require that the user has the ability to create branches on a repo-level scope
    permit(Permission.CREATE_LOCK, Scope.REPO)

    // require that the given branch does not exist before attempting to create it
    require("lockNotExists") {
        handler = { layer1 -> "The provided lock <${layer1.prefixes["morl"]}> already exists." to HttpStatusCode.Conflict }
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

suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createLock() {
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

    // extend the default conditions with requirements for user-specified ref or commit
    val localConditions = DEFAULT_CONDITIONS.appendRefOrCommit()
    createRefCreatingTransaction(localConditions, "morl")
    try {
        val constructModel = validateRefCreatingTransaction(localConditions)

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
        val autoPolicyCurie = "m-policy:AutoLockOwner.${UUID.randomUUID()}"
        executeSparqlUpdate(
            """
        insert data {
            graph mor-graph:Metadata {
                $lockTriples
                
                morl: mms:commit ?_commitSource ;
                    mms:created ?_now ;
                    mms:snapshot ?_commitSnapshot .
            }
            
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
                raw(
                    """
                morl: ?morl_p ?morl_o .
            """
                )
            }
            where {
                // first group in a series of unions fetches intended outputs
                txn()

                raw(
                    """
                    graph mor-graph:Metadata {
                        morl: ?morl_p ?morl_o .
                    }
                """
                )
            }
        }
        //TODO shouldn't need conditions
        finalizeMutateTransaction(lockConstructString, REPO_CRUD_CONDITIONS, "morl", true)
    } finally {
        deleteTransaction()
    }

}
