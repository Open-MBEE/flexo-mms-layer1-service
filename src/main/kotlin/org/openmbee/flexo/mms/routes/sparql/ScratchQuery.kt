package org.openmbee.flexo.mms.routes.sparql

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.REPO_QUERY_CONDITIONS
import org.openmbee.flexo.mms.parseUserUpdateString
import org.openmbee.flexo.mms.processAndSubmitUserQuery
import org.openmbee.flexo.mms.routes.SCRATCHES_PATH
import org.openmbee.flexo.mms.server.sparqlQuery
import org.openmbee.flexo.mms.server.sparqlUpdate


/**
 * User submitted SPARQL Query to a scratch space
 */
fun Route.queryScratch() {
    sparqlQuery("$SCRATCHES_PATH/query") {
        parsePathParams {
            org()
            repo()
            branch()
            inspect()
        }

        processAndSubmitUserQuery(requestContext, "${prefixes["mor-graph"]}Scratch", REPO_QUERY_CONDITIONS)
    }

    sparqlUpdate("$SCRATCHES_PATH/update") {
        parsePathParams {
            org()
            repo()
            branch()
        }

        // parse update string
        val (
            deleteBgpString,
            insertBgpString,
            whereString,
        ) = parseUserUpdateString()

        // scope the update to the scratch named graph
        val updateString = buildSparqlUpdate {
            delete {
                graph("mor-graph:Scratch") {
                    raw(deleteBgpString)
                }
            }
            insert {
                graph("mor-graph:Scratch") {
                    raw(insertBgpString)
                }
            }
            where {
                graph("mor-graph:Scratch") {
                    raw(whereString)
                }
            }
        }

        val responseText = executeSparqlUpdate(updateString) {
            prefixes(prefixes)
        }

        call.respondText(responseText, status = HttpStatusCode.OK)
    }
}
