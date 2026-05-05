package org.openmbee.flexo.mms.routes.ldp

import com.linkedin.migz.MiGzInputStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.jena.rdf.model.ResourceFactory
import org.openmbee.flexo.mms.*
import java.io.ByteArrayInputStream


private val ROOT_COMMIT_RESOURCE = ResourceFactory.createResource("urn:mms:rootCommit")

private val IS_PROPERTY = ResourceFactory.createProperty("urn:mms:is")


/**
 * Result of materializing a model graph for a commit.
 *
 * @property graphIri The IRI of the model graph (either existing or newly created)
 * @property snapshotIri The IRI of the Model snapshot that references the graph
 */
data class MaterializedModel(
    val graphIri: String,
    val snapshotIri: String,
)


/**
 * Materializes a model graph for the given commit IRI. If a model graph already exists for the commit,
 * returns its IRI. Otherwise, finds the nearest ancestor commit with a model graph, copies it,
 * and applies intermediate patches to build the model graph for the target commit.
 *
 * This function is idempotent: if a model graph already exists for the commit, it will be reused.
 *
 * @param commitIri The IRI of the commit to materialize a model graph for
 * @param targetGraphIri The IRI of the graph where the materialized model should be stored
 * @return A [MaterializedModel] with the graph IRI and snapshot IRI
 */
suspend fun AnyLayer1Context.materializeModelGraph(commitIri: String, targetGraphIri: String): MaterializedModel {
    // Step 1: Check if the commit already has a model graph
    val existingGraphQuery = """
        select ?graph ?snapshot where {
            graph mor-graph:Metadata {
                ?lock mms:commit ?_commitIri ;
                    mms:snapshot ?snapshot .
                ?snapshot a mms:Model ;
                    mms:graph ?graph .
            }
        } limit 1
    """.trimIndent()

    val existingResult = executeSparqlSelectOrAsk(existingGraphQuery) {
        prefixes(prefixes)
        iri("_commitIri" to commitIri)
    }

    val existingBindings = parseSparqlResultsJsonSelect(existingResult)
    if (existingBindings.isNotEmpty()) {
        val existingGraph = existingBindings[0]["graph"]!!.jsonObject["value"]!!.jsonPrimitive.content
        val existingSnapshot = existingBindings[0]["snapshot"]!!.jsonObject["value"]!!.jsonPrimitive.content
        return MaterializedModel(graphIri = existingGraph, snapshotIri = existingSnapshot)
    }
    //TODO need to test this, currently all commits generate a model graph so this always return before
    // Step 2: Find nearest ancestor commit with a model graph and copy it to the target graph
    val sequenceUpdate = """
        insert {
            # copy origin graph
            graph ?_targetGraph {
                ?origin_s ?origin_p ?origin_o .
            }

            # save state for next queries
            graph m-graph:Transactions {
                mt:sequence
                    mms-txn:originCommit ?originCommit ;
                    mms-txn:originSnapshot ?originSnapshot ;
                    mms-txn:originGraph ?originGraph ;
                    .
            }
        }
        where {
            graph mor-graph:Metadata {
                # commit's ancestors
                ?_commitIri a mms:Commit ;
                    mms:parent+ ?originCommit .
        
                # pick the closest commit which also has a snapshot by excluding 'heirs'
                filter not exists {
                    ?_commitIri mms:parent+ ?heirCommit .
        
                    ?heirCommit mms:parent+ ?originCommit .
        
                    ?heirLock mms:commit ?heirCommit ;
                        mms:snapshot ?heirSnapshot .
                    ?heirSnapshot a mms:Model .
                }
        
                # traverse to snapshot
                ?originLock mms:commit ?originCommit ;
                    mms:snapshot ?originSnapshot .
        
                # resolve to origin graph (must be a Model snapshot)
                ?originSnapshot a mms:Model ;
                    mms:graph ?originGraph .
            }

            # select contents of origin graph
            optional {
                graph ?originGraph {
                    ?origin_s ?origin_p ?origin_o .
                }
            }
        }
    """.trimIndent()

    executeSparqlUpdate(sequenceUpdate) {
        prefixes(prefixes)
        iri(
            "_commitIri" to commitIri,
            "_targetGraph" to targetGraphIri,
        )
    }

    // Step 3: Construct all intermediate commits (deltas) between ancestor and target
    val deltasConstruct = """
        construct {
            ?deltaCommit ?delta_p ?delta_o .
        
            ?deltaData ?data_p ?data_o .
            
            <${ROOT_COMMIT_RESOURCE.uri}> <${IS_PROPERTY.uri}> ?rootCommit .
        } where {
            # restore state from previous transaction
            graph m-graph:Transactions {
                mt:sequence
                    mms-txn:originCommit ?originCommit ;
                    mms-txn:originSnapshot ?originSnapshot ;
                    mms-txn:originGraph ?originGraph ;
                    .
            }
        
            graph mor-graph:Metadata {
                # select all prior delta commits...
                ?_commitIri mms:parent* ?deltaCommit .
        
                # ...that occur after the origin commit
                ?deltaCommit mms:parent+ ?originCommit ;
                    mms:data ?deltaData ;
                    ?delta_p ?delta_o .
        
                # all delta commit data
                ?deltaData ?data_p ?data_o.
                
                # 'root' commit
                ?rootCommit mms:parent ?originCommit .
            }
        }
    """.trimIndent()

    val deltasResponseText = executeSparqlConstructOrDescribe(deltasConstruct) {
        prefixes(prefixes)
        iri(
            "_commitIri" to commitIri,
        )
    }

    // Step 4: Parse deltas and apply patches in order
    val updates = mutableListOf<String>()
    parseConstructResponse(deltasResponseText) {
        // find root commit
        val rootCommits = model.listObjectsOfProperty(ROOT_COMMIT_RESOURCE, IS_PROPERTY).toList()
        if (rootCommits.size != 1) throw Http500Excpetion("Failed to determine commit history")

        // set initial commit resource
        var commit = rootCommits[0].asResource()

        // iterate through all commits from oldest to newest
        while (true) {
            // get patch body
            val patches =
                model.listObjectsOfProperty(commit.extractExactly1Uri(MMS.data), MMS.patch).toList()
            if (patches.size != 1) throw Http500Excpetion("Commit data missing patch string")

            // ref literal
            val patchLiteral = patches[0].asLiteral()

            // compressed sparql gz
            val patchString = patchLiteral.string

            // add to update strings
            updates.add(patchString)

            // traverse to child commit
            val children = model.listSubjectsWithProperty(MMS.parent, commit).toList()
            if (children.isEmpty()) break

            // repeat
            commit = children[0]
        }
    }

    // Step 5: Apply all patches in sequence to the target graph
    executeSparqlUpdate(updates.joinToString(" ;\n")) {
        prefixes(prefixes)
        iri(
            "__mms_model" to targetGraphIri
        )
    }

    // Step 6: Save snapshot metadata - create a Lock for this commit pointing to the new Model snapshot
    val modelSnapshot = "${prefixes["mor-snapshot"]}Model.${transactionId}"
    executeSparqlUpdate(
        """
        insert data {
            graph mor-graph:Metadata {
                mor-lock:Commit.${transactionId} a mms:Lock ;
                    mms:commit ?_commitIri ;
                    mms:snapshot ?_mdlSnapshot ;
                    .

                ?_mdlSnapshot a mms:Model ;
                    mms:graph ?_targetGraph ;
                    .
            }
        }
        """
    ) {
        prefixes(prefixes)
        iri(
            "_commitIri" to commitIri,
            "_targetGraph" to targetGraphIri,
            "_mdlSnapshot" to modelSnapshot,
        )
    }

    // Clean up the transaction sequence state
    executeSparqlUpdate("""
        delete where {
            graph m-graph:Transactions {
                mt:sequence ?p ?o .
            }
        }
    """)

    return MaterializedModel(graphIri = targetGraphIri, snapshotIri = modelSnapshot)
}
