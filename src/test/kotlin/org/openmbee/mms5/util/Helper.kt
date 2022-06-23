package org.openmbee.mms5.util


fun createOrg(orgId: String, orgName: String) {
    withTest {
        put("/orgs/$orgId") {
            setTurtleBody("""
                <> dct:title "$orgName"@en .
            """.trimIndent())
        }
    }
}

fun createRepo(repoId: String, repoName: String, orgId: String) {
    withTest {
        put("/orgs/$orgId/repos/$repoId") {
            setTurtleBody("""
                <> dct:title "$repoName"@en .
            """.trimIndent())
        }
    }
}
