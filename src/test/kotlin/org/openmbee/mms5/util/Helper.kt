package org.openmbee.mms5.util

import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import io.ktor.server.testing.*


fun createOrg(orgId: String, orgName: String): TestApplicationCall {
    return withTest {
        httpPut("/orgs/$orgId") {
            setTurtleBody("""
                <> dct:title "$orgName"@en .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus HttpStatusCode.OK
            // assert it exists
            httpGet("/orgs/$orgId") {}.apply {
                response shouldHaveStatus 200
            }
        }
    }
}

fun createRepo(repoId: String, repoName: String, orgId: String): TestApplicationCall {
    return withTest {
        httpPut("/orgs/$orgId/repos/$repoId") {
            setTurtleBody("""
                <> dct:title "$repoName"@en .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus HttpStatusCode.OK
        }
    }
}

fun createBranch(branchId: String, branchName: String, fromRefId: String, repoId: String, orgId: String): TestApplicationCall {
    return withTest {
        httpPut("/orgs/$orgId/repos/$repoId/branches/$branchId") {
            setTurtleBody("""
                <> dct:title "$branchName"@en .
                <> mms:ref <./$fromRefId> .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus HttpStatusCode.OK
        }
    }
}

fun updateModel(sparql: String, branchId: String, repoId: String, orgId: String):  TestApplicationCall {
    return withTest {
        httpPost("/orgs/$orgId/repos/$repoId/branches/$branchId/update") {
            setSparqlUpdateBody(sparql)
        }.apply {
            response shouldHaveStatus HttpStatusCode.OK
        }
    }
}
