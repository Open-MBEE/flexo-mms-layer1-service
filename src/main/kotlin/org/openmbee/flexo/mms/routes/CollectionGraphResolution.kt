package org.openmbee.flexo.mms.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.flexo.mms.AnyLayer1Context


/**
 * SPARQL SELECT query that resolves a collection's `mms:collects` targets to their model graph IRIs.
 *
 * For each collected ref, the ref IRI encodes its repo path. We look up the repo in `m-graph:Cluster`,
 * derive the repo metadata graph IRI (`{repoIri}/graphs/Metadata`), then query within that metadata
 * graph to resolve the ref to its model graph:
 *
 * - Branch: ref → commit → snapshot (prefer mms:Model, fallback mms:Staging) → mms:graph
 * - Lock:   ref → commit → snapshot (mms:Model) → mms:graph
 * - Scratch: graph IRI is `{repoIri}/graphs/Scratch.{scratchId}`
 */
val COLLECTION_GRAPH_RESOLUTION_SPARQL = """
    select ?graph where {
        graph m-graph:Cluster {
            moc: a mms:Collection ;
                 mms:collects ?ref .

            # find the repo that owns this ref (ref IRI is a child path of the repo IRI)
            ?repo a mms:Repo .
            filter(strstarts(str(?ref), concat(str(?repo), "/")))
        }

        # derive the repo metadata graph IRI from the repo IRI
        bind(iri(concat(str(?repo), "/graphs/Metadata")) as ?repoMetaGraph)

        {
            # branch case: resolve through commit to snapshot, prefer Model over Staging
            graph ?repoMetaGraph {
                ?ref a mms:Branch ;
                     mms:commit ?commit .
                ?commit ^mms:commit/mms:snapshot ?snapshot .
                ?snapshot mms:graph ?graph .

                # prefer the model snapshot
                {
                    ?snapshot a mms:Model .
                }
                # use staging snapshot if model is not ready
                union {
                    ?snapshot a mms:Staging .
                    filter not exists {
                        ?snapshot ^mms:snapshot/mms:commit/^mms:commit/mms:snapshot/a mms:Model .
                    }
                }
            }
        } union {
            # lock case: ref is in the repo metadata graph
            graph ?repoMetaGraph {
                ?ref a mms:Lock ;
                     mms:commit ?lockCommit .
                ?ref mms:snapshot ?lockSnapshot .
                ?lockSnapshot a mms:Model ;
                              mms:graph ?graph .
            }
        } union {
            # scratch case: ref is in the repo metadata graph, graph IRI derived from repo
            graph ?repoMetaGraph {
                ?ref a mms:Scratch ;
                     mms:id ?scratchId .
            }
            bind(iri(concat(str(?repo), "/graphs/Scratch.", ?scratchId)) as ?graph)
        }
    }
""".trimIndent()


/**
 * Resolves a collection's `mms:collects` targets to their model graph IRIs.
 *
 * Executes the graph resolution SPARQL query and parses the result bindings
 * to return a list of graph IRI strings.
 *
 * @return list of resolved graph IRI strings
 */
suspend fun AnyLayer1Context.resolveCollectionGraphIris(): List<String> {
    val graphSelectResponse = executeSparqlSelectOrAsk(COLLECTION_GRAPH_RESOLUTION_SPARQL) {
        acceptReplicaLag = true
        prefixes(prefixes)
    }

    return Json.parseToJsonElement(graphSelectResponse)
        .jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray
        .map { it.jsonObject["graph"]!!.jsonObject["value"]!!.jsonPrimitive.content }
}
