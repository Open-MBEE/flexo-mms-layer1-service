package org.openmbee.mms5.util

import io.kotest.assertions.ktor.shouldHaveStatus


fun createOrg(orgId: String, orgName: String) {
    withTest {
        httpPut("/orgs/$orgId") {
            setTurtleBody("""
                <> dct:title "$orgName"@en .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus 200
        }
    }
}

fun createRepo(repoId: String, repoName: String, orgId: String) {
    withTest {
        httpPut("/orgs/$orgId/repos/$repoId") {
            setTurtleBody("""
                <> dct:title "$repoName"@en .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus 200
        }
    }
}
