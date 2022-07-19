package org.openmbee.mms5

import org.openmbee.mms5.util.*

class ModelQuery : ModelAny() {
    init {
        "query data from model" {
            val update = commitModel(masterPath, sparqlUpdate)
            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult)
                }
            }
        }
        "query result is different between master and branch" {
            commitModel(masterPath, sparqlUpdate)
            createBranch(repoPath, "master", branchId, branchName)
            commitModel(masterPath, sparqlUpdate2)
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
        "query result is different between master and lock" {
            commitModel(masterPath, sparqlUpdate)
            createLock(repoPath, "branches/master", lockId)
            commitModel(masterPath, sparqlUpdate2)
            withTest {
                //branch model does not have second updates
                httpPost("$lockPath/query") {
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
