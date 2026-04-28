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

    // Step 2: Query model graph IRIs that are exclusive to this lock (not shared by other locks)
    val exclusiveGraphQuery = """
        select ?modelGraph where {
            graph mor-graph:Metadata {
                morl: mms:snapshot ?snapshot .
                ?snapshot mms:graph ?modelGraph .
                filter not exists {
                    ?otherLock mms:snapshot ?snapshot .
                    filter(?otherLock != morl:)
                }
            }
        }
    """

    val graphResults = executeSparqlSelectOrAsk(exclusiveGraphQuery) {
        prefixes(prefixes)
    }
    val exclusiveGraphIris = parseSparqlResultsJsonSelect(graphResults).mapNotNull { binding ->
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
            txn()
        }
    }

    val constructResponseText = executeSparqlConstructOrDescribe(constructString)
    validateTransaction(constructResponseText, localConditions, null, "morl")

    // Step 5: Respond with the transaction result
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)

    // Step 6: Clean up - drop exclusive model graphs and transaction
    for (graphIri in exclusiveGraphIris) {
        executeSparqlUpdate("drop silent graph <$graphIri>")
    }

    executeSparqlUpdate("""
        delete where {
            graph m-graph:Transactions {
                mt: ?p ?o .
            }
        }
    """)
}
