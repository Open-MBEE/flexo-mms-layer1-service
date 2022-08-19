package org.openmbee.mms5

import io.kotest.core.spec.style.describeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import kotlinx.coroutines.delay
import org.apache.jena.rdf.model.ResourceFactory
import org.openmbee.mms5.util.*
import java.util.*

class BranchCreate : RefAny() {
    init {
        "reject invalid branch id" {
            withTest {
                httpPut("/orgs/$orgId/repos/$repoId/branches/bad branch id") {
                    setTurtleBody(validBranchBodyFromMaster)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        mapOf(
            "rdf:type" to "mms:NotBranch",
            "mms:id" to "\"not-$branchId\"",
            "mms:etag" to "\"${UUID.randomUUID()}\"",
            "mms:ref" to "<./nosuchbranch>"
        ).forEach { (pred, obj) ->
            "reject wrong $pred" {
                withTest {
                    httpPut(branchPath) {
                        var ref = "<> mms:ref <./master> ."
                        if (pred == "mms:ref")
                            ref = ""
                        setTurtleBody("""
                            <> dct:title "$branchName"@en .
                            <> $pred $obj .
                            $ref
                        """.trimIndent())
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }
        }

        "create branch from master after a commit to master" {
            val update = commitModel(masterPath, """
                insert data {
                    <http://somesub.com> <http://somepred.com> 5 . 
                }
            """.trimIndent())

            val commit = update.response.headers[HttpHeaders.ETag]

            withTest {
                httpPut(branchPath) {
                    setTurtleBody("""
                        $validBranchBodyFromMaster
                    """.trimIndent())
                }.apply {
                    validateCreateBranchResponse(commit!!)
                }
            }
        }

        "create branch from empty master" {
            withTest {
                httpPut(branchPath) {
                    setTurtleBody("""
                        $validBranchBodyFromMaster
                    """.trimIndent())
                }.apply {
                    validateCreateBranchResponse(repoEtag)
                }
            }
        }

        "insert, replace x 4, branch on 2nd" {
            val init = commitModel(masterPath, """
                insert data {
                    <urn:s> <urn:p> 1 . 
                }
            """.trimIndent())

            val initCommitId = init.response.headers[HttpHeaders.ETag]


            val commitIds = mutableListOf<String>();

            suspend fun replaceCounterValue(value: Int): String {
                val update = commitModel(masterPath, """
                    delete where {
                        <urn:s> <urn:p> ?previous .
                    } ;
                    insert data {
                        <urn:s> <urn:p> $value . 
                    }
                """.trimIndent()
                )

                val commitId = update.response.headers[HttpHeaders.ETag]!!

                commitIds.add(commitId)

                // wait for interim lock to be deleted
                delay(2_000L)

                return commitId;
            }

            val restoredValue = 2
            val restoreCommitId = replaceCounterValue(restoredValue)

            for(index in 3..5) {
                replaceCounterValue(index)
            }

            withTest {
                // create branch and validate
                httpPut(branchPath) {
                    setTurtleBody("""
                        ${title(branchName)}
                        <> mms:commit mor-commit:$restoreCommitId .
                    """.trimIndent()
                    )
                }.apply {
                    validateCreateBranchResponse(restoreCommitId)
                }

                // assert the resultant model is in the correct state
                val refPath = "$branchPath/graph"
                httpGet(refPath) {
                    addHeader(HttpHeaders.Accept, RdfContentTypes.Turtle.toString())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    val model = KModel()
                    parseTurtle(response.content!!, model, branchPath)

                    val s = ResourceFactory.createResource("urn:s")
                    val p = ResourceFactory.createProperty("urn:p")
                    val values = model.listObjectsOfProperty(s, p).toList()

                    values shouldHaveSize 1
                    values[0].asLiteral().string shouldBe "$restoredValue"
                }
            }
        }

        "model load x 3, branch on 2" {
            val load1 = loadModel(masterPath, """
                <urn:s> <urn:p> 1 .
            """.trimIndent())

            delay(500L)

            val load2 = loadModel(masterPath, """
                <urn:s> <urn:p> 2 .
            """.trimIndent())

            val commitId2 = load2.response.headers[HttpHeaders.ETag]!!

            delay(500L)

            val load3 = loadModel(masterPath, """
                <urn:s> <urn:p> 3 .
            """.trimIndent())

            delay(500L)

            withTest {
                httpPut(branchPath) {
                    setTurtleBody("""
                        ${title(branchName)}
                        <> mms:commit mor-commit:${commitId2} .
                    """.trimIndent())
                }.apply {
                    validateCreateBranchResponse(commitId2)
                }


                val refPath = "$branchPath/graph"
                httpGet(refPath) {
                    addHeader(HttpHeaders.Accept, RdfContentTypes.Turtle.toString())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    val model = KModel()
                    parseTurtle(response.content!!, model, branchPath)

                    val s = ResourceFactory.createResource("urn:s")
                    val p = ResourceFactory.createProperty("urn:p")
                    val values = model.listObjectsOfProperty(s, p).toList()

                    values shouldHaveSize 1
                    values[0].asLiteral().string shouldBe "2"
                }
            }
        }
    }
}
