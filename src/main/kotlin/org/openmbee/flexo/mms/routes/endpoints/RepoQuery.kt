package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.plugins.sparqlQuery


/**
 * User submitted SPARQL Query to a specific repo
 */
fun Route.queryRepo() {
    sparqlQuery("/orgs/{orgId}/repos/{repoId}/query/{inspect?}") {
        parsePathParams {
            org()
            repo()
            inspect()
        }

        processAndSubmitUserQuery(requestContext, prefixes["mor"]!!, REPO_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        }, false, prefixes["mor"])
    }
}
