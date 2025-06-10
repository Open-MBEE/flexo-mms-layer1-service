import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.GspLayer1Context
import org.openmbee.flexo.mms.server.GspMutateResponse

/**
 * Overwrites the scratch graph
 */
suspend fun GspLayer1Context<GspMutateResponse>.loadScratch() {
    parsePathParams {
        org()
        repo()
        scratch()
    }
    // auth check
    checkModelQueryConditions(targetGraphIri = prefixes["mors"], conditions = SCRATCH_UPDATE_CONDITIONS.append {
        assertPreconditions(this)
    })

    // load triples directly into mor-graph:Scratch
    loadGraph("${prefixes["mor-graph"]}Scratch.$scratchId", "$orgId/$repoId/Scratch.$scratchId.ttl")

    // close response
    call.respondText("", status = HttpStatusCode.NoContent)
}