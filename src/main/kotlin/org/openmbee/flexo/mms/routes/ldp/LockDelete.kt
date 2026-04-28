package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpDeleteResponse


suspend fun LdpDcLayer1Context<LdpDeleteResponse>.deleteLock() {
    // Step 1: Check for collection references before deleting
    val collectionCheckQuery = """
        select ?collection where {
            graph m-graph:Cluster {
                ?collection a mms:Collection ;
                    mms:collects morl: .
            }
        }
    """

    val checkResults = executeSparqlSelectOrAsk(collectionCheckQuery) {
        prefixes(prefixes)
    }
    val bindings = parseSparqlResultsJsonSelect(checkResults)
    if (bindings.isNotEmpty()) {
        val collectionIris = bindings.mapNotNull { binding ->
            binding["collection"]?.jsonObject?.get("value")?.jsonPrimitive?.content
        }
        throw HttpException(
            "Cannot delete lock <${prefixes["morl"]}>: it is referenced by collection(s) ${collectionIris.joinToString(", ") { "<$it>" }}",
            HttpStatusCode.Conflict
        )
    }

    // Step 2: Save model graph IRIs before deletion (metadata will be removed)
    val modelGraphQuery = """
        select ?modelGraph where {
            graph mor-graph:Metadata {
                morl: mms:snapshot ?snapshot .
                ?snapshot mms:graph ?modelGraph .
            }
        }
    """

    val modelGraphResults = executeSparqlSelectOrAsk(modelGraphQuery) {
        prefixes(prefixes)
    }
    val candidateGraphIris = parseSparqlResultsJsonSelect(modelGraphResults).mapNotNull { binding ->
        binding["modelGraph"]?.jsonObject?.get("value")?.jsonPrimitive?.content
    }

    // Step 3: Execute the delete
    val localConditions = LOCK_DELETE_CONDITIONS

    val updateString = buildSparqlUpdate {
        compose {
            txn()
            conditions(localConditions)

            raw(dropLock())
        }
    }

    log.info(updateString)
    executeSparqlUpdate(updateString)

    // Step 4: Validate the transaction
    val constructString = buildSparqlQuery {
        construct {
            txn()
        }
        where {
            txnOrInspections(null, localConditions) {}
        }
    }

    val constructResponseText = executeSparqlConstructOrDescribe(constructString)
    validateTransaction(constructResponseText, localConditions, null, "morl")

    // Step 5: Respond with the transaction result
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)

    // Step 6: Clean up - drop model graphs no longer referenced by any snapshot
    for (graphIri in candidateGraphIris) {
        val stillReferencedQuery = """
            ask where {
                graph mor-graph:Metadata {
                    ?snapshot mms:graph <$graphIri> .
                }
            }
        """
        val stillReferenced = parseSparqlResultsJsonAsk(
            executeSparqlSelectOrAsk(stillReferencedQuery) { prefixes(prefixes) }
        )
        if (!stillReferenced) {
            executeSparqlUpdate("drop silent graph <$graphIri>")
        }
    }

    executeSparqlUpdate("""
        delete where {
            graph m-graph:Transactions {
                mt: ?p ?o .
            }
        }
    """)
}
