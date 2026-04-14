package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.sparql.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse
import java.time.Instant
import java.util.UUID


// default starting conditions for any calls to create a branch
private val DEFAULT_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    // require that the user has the ability to create branches on a repo-level scope
    permit(Permission.CREATE_BRANCH, Scope.REPO)

    // require that the given branch does not exist before attempting to create it
    require("branchNotExists") {
        handler = { layer1 -> "The provided branch <${layer1.prefixes["morb"]}> already exists." to HttpStatusCode.BadRequest }
        """
            # branch must not yet exist
            filter not exists {
                graph mor-graph:Metadata {
                    morb: a mms:Branch .
                }
            }
        """
    }
}



//suspend fun AnyLayer1Context.createBranch(usedPost: Boolean = false) {
suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createBranch(usedPost: Boolean = false) {
    // process RDF body from user about this new branch
    val branchTriples = filterIncomingStatements("morb") {
        // relative to this branch node
        branchNode().apply {
            // assert and normalize ref/commit triples
            normalizeRefOrCommit(this)

            // sanitize statements
            sanitizeCrudObject {
                setProperty(RDF.type, MMS.Branch)
                setProperty(MMS.id, branchId!!)
                setProperty(MMS.etag, transactionId)
                setProperty(MMS.createdBy, userNode())
            }
        }
    }

    // extend the default conditions with requirements for user-specified ref or commit
    val localConditions = DEFAULT_CONDITIONS.appendRefOrCommit()
    createRefCreatingTransaction(localConditions, "morb")
    try {
        val constructModel = validateRefCreatingTransaction(localConditions)

        // resolve the commit source IRI
        val resolvedCommitSource = commitSource ?: run {
            val transactionNode = constructModel.createResource(prefixes["mt"])
            transactionNode.listProperties(MMS.TXN.commitSource).toList().firstOrNull()
                ?.`object`?.asResource()?.uri
                ?: throw Http500Excpetion("Transaction missing commit source")
        }

        // predetermine snapshot graphs
        val modelGraph = "${prefixes["mor-graph"]}Model.${transactionId}"
        val stagingGraph = "${prefixes["mor-graph"]}Staging.${transactionId}"

        // --- graph setup (before branch metadata) ---
        // materialize the model graph for the commit (idempotent — reuses existing if available)
        val materialized = materializeModelGraph(resolvedCommitSource, modelGraph)

        // copy materialized graph to new branch's staging graph
        executeSparqlUpdate(
            """
        copy silent graph <${materialized.graphIri}> to graph ?_stgGraph ;
        
        insert data {
            graph m-graph:Graphs {
                ?_stgGraph a mms:SnapshotGraph .
            }
        }
    """
        ) {
            prefixes(prefixes)
            iri(
                "_stgGraph" to stagingGraph,
            )
        }

        // --- insert branch metadata + auto policy last (after graph setup) ---
        val autoPolicyCurie = "m-policy:AutoBranchOwner.${UUID.randomUUID()}"
        executeSparqlUpdate(
            """
        insert data {
            graph mor-graph:Metadata {
                $branchTriples
                
                morb: mms:commit ?_commitSource ;
                      mms:created ?_now ;
                      mms:snapshot ?_stgSnapshot .
                      
                ?_stgSnapshot a mms:Staging ;
                    mms:graph ?_stgGraph ;
                    .
            }
            
            graph m-graph:Transactions {
                mt: mms:createdPolicy $autoPolicyCurie .
            }
            
            graph m-graph:AccessControl.Policies {
                $autoPolicyCurie a mms:Policy ;
                    mms:subject mu: ;
                    mms:scope morb: ;
                    mms:role mms-object:Role.AdminBranch ;
                    .
            }
        }
    """
        ) {
            prefixes(prefixes)
            iri(
                "_commitSource" to resolvedCommitSource,
                "_stgGraph" to stagingGraph,
                "_stgSnapshot" to "${prefixes["mor-snapshot"]}Staging.${transactionId}",
            )
            datatyped(
                "_now" to (Instant.now().toString() to XSDDatatype.XSDdateTime),
            )
        }

        // --- finalize: fetch branch triples for response ---
        val branchConstructString = buildSparqlQuery {
            construct {
                // all the details about this transaction
                txn()

                // all the properties about this branch
                raw(
                    """
                    morb: ?morb_p ?morb_o .
                    """
                )
            }
            where {
                txn()

                raw(
                    """
                    graph mor-graph:Metadata {
                        morb: ?morb_p ?morb_o .
                    }
                """
                )
            }
        }
        //TODO shoudln't need conditions
        finalizeMutateTransaction(branchConstructString, REPO_CRUD_CONDITIONS, "morb", true)

    } finally {
        deleteTransaction()
    }

}
