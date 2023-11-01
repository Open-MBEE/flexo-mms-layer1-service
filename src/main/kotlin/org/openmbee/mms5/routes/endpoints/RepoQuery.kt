package org.openmbee.mms5.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Triple
import org.apache.jena.sparql.syntax.ElementGroup
import org.apache.jena.sparql.syntax.ElementNamedGraph
import org.apache.jena.sparql.syntax.ElementTriplesBlock
import org.openmbee.mms5.*
import java.util.*


fun Route.queryRepo() {
    post("/orgs/{orgId}/repos/{repoId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_REPO) {
            pathParams {
                org()
                repo()
                inspect()
            }

            checkPrefixConflicts()

            // use request body for SPARQL query
            val inputQueryString = "$prefixes\n$requestBody"

            queryModel(inputQueryString, prefixes["mor"]!!, REPO_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            }, false, prefixes["mor"])
        }
    }
}
