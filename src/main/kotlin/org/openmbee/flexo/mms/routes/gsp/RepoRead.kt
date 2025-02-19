package org.openmbee.flexo.mms.routes.gsp

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.GspLayer1Context
import org.openmbee.flexo.mms.server.GspReadResponse
import org.openmbee.flexo.mms.server.SparqlQueryRequest


suspend fun GspLayer1Context<GspReadResponse>.readRepo(head: Boolean) {
    parsePathParams {
        org()
        repo()
    }


    // HEAD method
    if (head) {
        checkModelQueryConditions("${prefixes["mor-graph"]}Metadata", prefixes["mor"]!!, REPO_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
        call.respond(HttpStatusCode.OK)
    }
    // GET
    else {
        val construct = """
            construct { ?s ?p ?o } WHERE { ?s ?p ?o }
        """.trimIndent()
        val requestContext = SparqlQueryRequest(call, construct, setOf(), setOf())
        processAndSubmitUserQuery(requestContext, prefixes["mor"]!!, REPO_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
    }
}
