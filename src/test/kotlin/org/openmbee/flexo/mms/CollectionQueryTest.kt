package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory


class CollectionQueryTest : CollectionAny() {
    override val logger = LoggerFactory.getLogger(CollectionQueryTest::class.java)

    init {
        "GET $demoCollectionPath/query - non-existent collection" {
            testApplication {
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "POST $demoCollectionPath/query - query across collected graphs" {
            testApplication {
                // load data into the repo's master branch
                httpPost("$demoRepoPath/branches/master/update") {
                    setSparqlUpdateBody(insertAliceRex)
                }

                // create collection that collects the master branch
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // query the collection
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "POST $demoCollectionPath/query/inspect - inspect generated query" {
            testApplication {
                // create collection
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // inspect query
                httpPost("$demoCollectionPath/query/inspect") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "GET $demoCollectionPath/graph - get union graph" {
            testApplication {
                // load data
                httpPost("$demoRepoPath/branches/master/update") {
                    setSparqlUpdateBody(insertAliceRex)
                }

                // create collection
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // get the union graph
                httpGet("$demoCollectionPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }
    }
}
