package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
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

            // auto-inject default prefixes
            val inputQueryString = "$prefixes\n$requestBody"

            queryModel(inputQueryString, prefixes["mor"]!!, REPO_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            }, prefixes["mor"])
        }
    }
}
