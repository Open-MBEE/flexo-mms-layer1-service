package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.openmbee.mms5.*

private const val SPARQL_BGP_STAGING_EXISTS = """
    graph mor-graph:Metadata {
        # select the latest commit from the current named ref
        morb: mms:commit ?baseCommit ;
            .
    
        # and its staging snapshot
        morb: mms:snapshot ?staging .
        ?staging a mms:Staging ;
            mms:graph ?stagingGraph ;
            .
    
        optional {
            # optionally, it's model snapshot
            morb: mms:snapshot ?model .
            ?model a mms:Model ;
                mms:graph ?modelGraph ;
                .
        }
    }
"""

private val DEFAULT_UPDATE_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    permit(Permission.UPDATE_BRANCH, Scope.BRANCH)

    require("stagingExists") {
        handler = { prefixes -> "The destination branch <${prefixes["morb"]}> is corrupt. No staging snapshot found." }

        SPARQL_BGP_STAGING_EXISTS
    }
}


fun Application.loadBranch() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/graph") {
            call.mmsL1(Permission.UPDATE_BRANCH) {
                pathParams {
                    org()
                    repo()
                    branch()
                }

                val model = KModel(prefixes).apply {
                    parseTurtle(requestBody,this)
                    clearNsPrefixMap()
                }

                val localConditions = DEFAULT_UPDATE_CONDITIONS

                val updateLoadString = buildSparqlUpdate {
                    insert {
                        txn()

                        graph("?_loadGraph") {
                            raw(model.stringify())
                        }
                    }
                    where {
                        raw(*localConditions.requiredPatterns())
                    }
                }

                log.info(updateLoadString)

                executeSparqlUpdate(updateLoadString) {
                    iri(
                        "_loadGraph" to "${prefixes["mor-graph"]}Load.$transactionId",
                    )
                }

                // TODO: invoke createDiff()
                // TODO: convert diff graphs into SPARQL UPDATE string
                // TODO: invoke commitBranch() using update
            }
        }
    }
}