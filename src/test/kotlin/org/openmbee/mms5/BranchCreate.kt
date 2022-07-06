package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
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
            val update = updateModel("""
                insert data { 
                    <http://somesub.com> <http://somepred.com> 5 . 
                }
            """.trimIndent(), "master", repoId, orgId)
            val commit = update.response.headers[HttpHeaders.ETag]
            withTest {
                httpPut(branchPath) {
                    setTurtleBody(
                        """
                        $validBranchBodyFromMaster
                    """.trimIndent()
                    )
                }.apply {
                    validateCreateBranchResponse(commit!!)
                }
            }
        }
        "create branch from empty master" {
            withTest {
                httpPut(branchPath) {
                    setTurtleBody(
                        """
                        $validBranchBodyFromMaster
                    """.trimIndent()
                    )
                }.apply {
                    validateCreateBranchResponse(repoEtag)
                }
            }
        }
    }
}
