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
    }
}
