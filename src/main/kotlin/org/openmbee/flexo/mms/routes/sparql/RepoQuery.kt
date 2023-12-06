package org.openmbee.flexo.mms.routes.sparql

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.REPO_QUERY_CONDITIONS
import org.openmbee.flexo.mms.assertPreconditions
import org.openmbee.flexo.mms.server.sparqlQuery
import org.openmbee.flexo.mms.processAndSubmitUserQuery


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
