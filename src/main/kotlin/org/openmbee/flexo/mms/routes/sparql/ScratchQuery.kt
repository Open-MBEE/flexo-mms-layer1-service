package org.openmbee.flexo.mms.routes.sparql

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.REPO_QUERY_CONDITIONS
import org.openmbee.flexo.mms.SCRATCH_QUERY_CONDITIONS
import org.openmbee.flexo.mms.parseUserUpdateString
import org.openmbee.flexo.mms.processAndSubmitUserQuery
import org.openmbee.flexo.mms.routes.SCRATCHES_PATH
import org.openmbee.flexo.mms.server.sparqlQuery
import org.openmbee.flexo.mms.server.sparqlUpdate


/**
 * User submitted SPARQL Query to a scratch space
 */
fun Route.queryScratch() {
    sparqlQuery("$SCRATCHES_PATH/{scratchId}/query") {
        parsePathParams {
            org()
            repo()
            scratch()
            inspect()
        }

        processAndSubmitUserQuery(requestContext, "${prefixes["mor-graph"]}Scratch.$scratchId", SCRATCH_QUERY_CONDITIONS)
    }

    sparqlUpdate("$SCRATCHES_PATH/{scratchId}/update") {
        parsePathParams {
            org()
            repo()
            scratch()
            branch()
        }

        // parse update string
        val (
            deleteBgpString,
            insertBgpString,
            whereString,
        ) = parseUserUpdateString()

        // construct the scratch's named graph IRI
        val scratchGraph = "mor-graph:Scratch.$scratchId"

        // scope the update to the scratch named graph
        val updateString = buildSparqlUpdate {
            delete {
                graph(scratchGraph) {
                    raw(deleteBgpString)
                }
            }
            insert {
                graph(scratchGraph) {
                    raw(insertBgpString)
                }
            }
            where {
                graph(scratchGraph) {
                    raw(whereString)
                }
            }
        }

        // execute the SPARQL UPDATE
        val responseText = executeSparqlUpdate(updateString) {
            prefixes(prefixes)
        }

        // forward response to client
        call.respondText(responseText, status = HttpStatusCode.OK)
    }
}
