package org.openmbee.flexo.mms.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.ldp.createOrReplaceCollection
import org.openmbee.flexo.mms.routes.ldp.getCollections
import org.openmbee.flexo.mms.routes.ldp.headCollections
import org.openmbee.flexo.mms.server.graphStoreProtocol
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

const val SPARQL_VAR_NAME_COLLECTION = "_collection"

const val COLLECTIONS_PATH = "/orgs/{orgId}/collections"

/**
 * Collection CRUD routing
 */
fun Route.crudCollections() {
    // all collections
    linkedDataPlatformDirectContainer(COLLECTIONS_PATH) {
        beforeEach = {
            parsePathParams {
                org()
            }
        }

        // state of all collections
        head {
            headCollections(true)
        }

        // read all collections
        get {
            getCollections(true)
        }

        // create a new collection
        post { slug ->
            // assert id is legal
            assertLegalId(slug)

            // set collection id on context
            collectionId = slug

            // create new collection
            createOrReplaceCollection()
        }
    }

    // specific collection
    linkedDataPlatformDirectContainer("$COLLECTIONS_PATH/{collectionId}") {
        beforeEach = {
            parsePathParams {
                org()
                collection()
            }
        }

        // state of a collection
        head {
            headCollections()
        }

        // read a collection
        get {
            getCollections()
        }

        // create or replace collection
        put {
            createOrReplaceCollection()
        }

//        // modify existing collection
//        patch {
////            guardedPatch(
////                updateRequest = it,
////                objectKey = "moc",
////                graph = "m-graph:Cluster",
////                preconditions = UPDATE_COLLECTION_CONDITIONS,
////            )
//        }
    }

    // GSP for collection graph (union of collected ref graphs)
    graphStoreProtocol("$COLLECTIONS_PATH/{collectionId}/graph") {
        beforeEach = {
            parsePathParams {
                org()
                collection()
            }
        }

        // GET: read union graph of all collected refs
        get {
            // check conditions
            checkModelQueryConditions(
                targetGraphIri = "urn:mms:collection:graph:placeholder",
                conditions = COLLECTION_QUERY_CONDITIONS
            )

            // look up the collection's mms:collects targets and resolve their model graph IRIs
            val graphSelectQuery = """
                select ?graph where {
                    graph m-graph:Cluster {
                        moc: a mms:Collection ;
                             mms:collects ?ref .
                    }
                    {
                        # branch case
                        ?ref a mms:Branch ;
                             mms:commit ?commit .
                        ?commit ^mms:commit/mms:snapshot ?snapshot .
                        ?snapshot a mms:Staging ;
                                  mms:graph ?graph .
                    } union {
                        # lock case
                        ?ref a mms:Lock ;
                             mms:commit ?lockCommit .
                        ?lockCommit ^mms:commit/mms:snapshot ?lockSnapshot .
                        ?lockSnapshot a mms:Model ;
                                      mms:graph ?graph .
                    }
                }
            """.trimIndent()

            val graphSelectResponse = executeSparqlSelectOrAsk(graphSelectQuery) {
                acceptReplicaLag = true
                prefixes(prefixes)
            }

            // parse bindings to get graph IRIs
            val graphIris = Json.parseToJsonElement(graphSelectResponse)
                .jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray
                .map { it.jsonObject["graph"]!!.jsonObject["value"]!!.jsonPrimitive.content }

            if (graphIris.isEmpty()) {
                // return empty turtle
                call.respondText("", contentType = RdfContentTypes.Turtle)
                return@get
            }

            // build CONSTRUCT query with FROM clauses for each graph
            val fromClauses = graphIris.joinToString("\n") { "FROM <$it>" }
            val constructQuery = """
                CONSTRUCT { ?s ?p ?o }
                $fromClauses
                WHERE { ?s ?p ?o }
            """.trimIndent()

            val constructResponse = executeSparqlConstructOrDescribe(constructQuery) {
                acceptReplicaLag = true
            }

            call.respondText(constructResponse, contentType = RdfContentTypes.Turtle)
        }
    }
}
