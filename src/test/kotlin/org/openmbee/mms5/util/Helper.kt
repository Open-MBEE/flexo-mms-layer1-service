package org.openmbee.mms5.util

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.Model
import org.openmbee.mms5.KModel
import org.openmbee.mms5.RdfContentTypes
import org.openmbee.mms5.parseTurtle


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

fun createRepo(orgPath: String, repoId: String, repoName: String): TestApplicationCall {
    return withTest {
        httpPut("$orgPath/repos/$repoId") {
            setTurtleBody("""
                <> dct:title "$repoName"@en .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus HttpStatusCode.OK
        }
    }
}

fun createBranch(repoPath: String, refId: String, branchId: String, branchName: String): TestApplicationCall {
    return withTest {
        httpPut("$repoPath/branches/$branchId") {
            setTurtleBody("""
                <> dct:title "$branchName"@en .
                <> mms:ref <./$refId> .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus HttpStatusCode.OK
        }
    }
}

fun createLock(repoPath: String, refPath: String, lockId: String): TestApplicationCall {
    return withTest {
        httpPut("$repoPath/locks/$lockId") {
            setTurtleBody("""
                <> mms:ref <$refPath> .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus HttpStatusCode.OK
        }
    }
}

fun commitModel(refPath: String, sparql: String):  TestApplicationCall {
    return withTest {
        httpPost("$refPath/update") {
            setSparqlUpdateBody(sparql)
        }.apply {
            response shouldHaveStatus HttpStatusCode.Created
        }
    }
}

fun loadModel(refPath: String, turtle: String): TestApplicationCall {
    return withTest {
        httpPost("$refPath/graph") {
            setTurtleBody(turtle)
        }.apply {
            response shouldHaveStatus HttpStatusCode.OK
        }
    }
}

