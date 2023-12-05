package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.plugins.sparqlQuery

/**
 * User submitted SPARQL Query to a specific lock
 */
fun Route.queryLock() {
    sparqlQuery("/orgs/{orgId}/repos/{repoId}/locks/{lockId}/query/{inspect?}") {
        parsePathParams {
            org()
            repo()
            lock()
            inspect()
        }

        processAndSubmitUserQuery(requestContext, prefixes["morl"]!!, LOCK_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
    }
}
