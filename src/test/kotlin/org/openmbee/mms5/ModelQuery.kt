package org.openmbee.mms5

import org.openmbee.mms5.util.*

class ModelQuery : ModelAny() {
    init {
        "query data from model" {
            val update = updateModel(sparqlUpdate, "master", repoId, orgId)
            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult)
                }
            }
        }
        "query result is different between master and branch" {
            updateModel(sparqlUpdate, "master", repoId, orgId)
            createBranch(branchId, branchName, "master", repoId, orgId)
            updateModel(sparqlUpdate2, "master", repoId, orgId)
            withTest {
                //branch model does not have second updates
                httpPost("$branchPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult)
                }
                //master model is updated
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult2)
                }
            }
        }
    }
}
