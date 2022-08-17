package org.openmbee.mms5

import io.kotest.core.spec.style.describeSpec
import io.ktor.http.*
import kotlinx.coroutines.delay
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

        "insert, replace, branch on 1" {
            val update1 = commitModel(
                masterPath, """
                insert data {
                    <urn:s> <urn:p> 5 . 
                }
            """.trimIndent()
            )

            val commitId = update1.response.headers[HttpHeaders.ETag]


            val update2 = commitModel(
                masterPath, """
                delete data {
                    <urn:s> <urn:p> 5 .
                } ;
                insert data {
                    <urn:s> <urn:p> 6 . 
                }
            """.trimIndent()
            )

            // wait for interim lock to be deleted
            delay(2_000L)

            withTest {
                httpPut(branchPath) {
                    setTurtleBody("""
                        ${title(branchName)}
                        <> mms:commit mor-commit:$commitId .
                    """.trimIndent()
                    )
                }.apply {
                    validateCreateBranchResponse(commitId!!)
                }
            }
        }
    }
}
