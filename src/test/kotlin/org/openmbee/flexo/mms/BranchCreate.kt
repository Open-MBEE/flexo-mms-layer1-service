package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.vocabulary.SchemaDO.application
import org.openmbee.flexo.mms.util.*
import java.util.*

class BranchCreate : RefAny() {
    init {
        "reject invalid branch id" {
            testApplication {
                httpPut("/orgs/$demoOrgId/repos/$demoRepoId/branches/bad branch id", true) {
                    setTurtleBody(withAllTestPrefixes(validBranchBodyFromMaster))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        mapOf(
            "rdf:type" to "mms:NotBranch",
            "mms:id" to "\"not-$demoBranchId\"",
            "mms:etag" to "\"${UUID.randomUUID()}\"",
            "mms:ref" to "<./nosuchbranch>"
        ).forEach { (pred, obj) ->
            "reject wrong $pred" {
                testApplication {
                    httpPut(demoBranchPath, true) {
                        var ref = "<> mms:ref <./master> ."
                        if (pred == "mms:ref")
                            ref = ""
                        setTurtleBody(withAllTestPrefixes("""
                            <> dct:title "$demoBranchName"@en .
                            <> $pred $obj .
                            $ref
                        """.trimIndent()))
                    }.apply {
                        this shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }
        }

        "create branch from master after a commit to master" {
            testApplication {
                val update = commitModel(masterBranchPath, """
                    insert data {
                        <mms:urn:s> <mms:urn:p> 5 . 
                    }
                """.trimIndent())

                val commit = update.headers[HttpHeaders.ETag]
                httpPut(demoBranchPath) {
                    setTurtleBody(withAllTestPrefixes(validBranchBodyFromMaster))
                }.apply {
                    validateCreateBranchResponse(commit!!)
                }
            }
        }

        "create branch from empty master" {
            testApplication {
                httpPut(demoBranchPath) {
                    setTurtleBody(withAllTestPrefixes(validBranchBodyFromMaster))
                }.apply {
                    validateCreateBranchResponse(repoEtag)
                }
            }
        }

        "insert, replace x 4, branch on 2nd" {
            testApplication {
                commitModel(masterBranchPath, """
                    insert data {
                        <urn:mms:s> <urn:mms:p> 1 . 
                    }
                """.trimIndent())
                val commitIds = mutableListOf<String>();
                suspend fun replaceCounterValue(value: Int): String {
                    val update = commitModel(masterBranchPath, """
                        delete where {
                            <urn:mms:s> <urn:mms:p> ?previous .
                        } ;
                        insert data {
                            <urn:mms:s> <urn:mms:p> $value . 
                        }
                        """.trimIndent()
                    )
                    val commitId = update.headers[HttpHeaders.ETag]!!
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
                // create branch and validate
                httpPut(demoBranchPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        ${title(demoBranchName)}
                        <> mms:commit mor-commit:$restoreCommitId .
                    """.trimIndent()))
                }.apply {
                    validateCreateBranchResponse(restoreCommitId)
                }

                // assert the resultant model is in the correct state
                val refPath = "$demoBranchPath/graph"
                httpGet(refPath) {
                    header(HttpHeaders.Accept, RdfContentTypes.Turtle.toString())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    val model = KModel()
                    parseTurtle(this.bodyAsText(), model, demoBranchPath)

                    val s = ResourceFactory.createResource("urn:mms:s")
                    val p = ResourceFactory.createProperty("urn:mms:p")
                    val values = model.listObjectsOfProperty(s, p).toList()

                    values shouldHaveSize 1
                    values[0].asLiteral().string shouldBe "$restoredValue"
                }
            }
        }

        "model load x 3, branch on 2" {
            testApplication {
                val load1 = loadModel(masterBranchPath, """
                    <urn:mms:s> <urn:mms:p> 1 .
                """.trimIndent())

                delay(500L)

                val load2 = loadModel(masterBranchPath, """
                    <urn:mms:s> <urn:mms:p> 2 .
                """.trimIndent())

                val commitId2 = load2.headers[HttpHeaders.ETag]!!

                delay(500L)

                val load3 = loadModel(masterBranchPath, """
                    <urn:mms:s> <urn:mms:p> 3 .
                """.trimIndent())

                delay(500L)
                httpPut(demoBranchPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        ${title(demoBranchName)}
                        <> mms:commit mor-commit:${commitId2} .
                    """.trimIndent()))
                }.apply {
                    validateCreateBranchResponse(commitId2)
                }


                val refPath = "$demoBranchPath/graph"
                httpGet(refPath) {
                    header(HttpHeaders.Accept, RdfContentTypes.Turtle.toString())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    val model = KModel()
                    parseTurtle(this.bodyAsText(), model, demoBranchPath)

                    val s = ResourceFactory.createResource("urn:mms:s")
                    val p = ResourceFactory.createProperty("urn:mms:p")
                    val values = model.listObjectsOfProperty(s, p).toList()

                    values shouldHaveSize 1
                    values[0].asLiteral().string shouldBe "2"
                }
            }
        }
    }
}
