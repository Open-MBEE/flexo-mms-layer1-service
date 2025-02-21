package org.openmbee.flexo.mms.util

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.*
import java.net.URLEncoder


fun createOrg(orgId: String, orgName: String): TestApplicationCall {
    return withTest {
        httpPut("/orgs/$orgId") {
            setTurtleBody("""
                <> dct:title "$orgName"@en .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus HttpStatusCode.Created

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
            response shouldHaveStatus HttpStatusCode.Created

            // assert it exists
            httpGet("/$orgPath/repos/$repoId") {}.apply {
                response shouldHaveStatus 200
            }
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
            response shouldHaveStatus HttpStatusCode.Created

            // assert it exists
            httpGet("/$repoPath/branches/$branchId") {}.apply {
                this.response shouldHaveStatus 200
            }
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
            response shouldHaveStatus HttpStatusCode.Created
        }
    }
}

fun createGroup(groupId: String, groupTitle: String): TestApplicationCall {
    return withTest {
        httpPut("/groups/${URLEncoder.encode(groupId, "UTF-8")}") {
            setTurtleBody("""
                <> dct:title "${groupTitle}"@en .
            """.trimIndent())
        }.apply {
            response shouldHaveStatus HttpStatusCode.Created
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
        httpPut("$refPath/graph") {
            setTurtleBody(turtle)
        }.apply {
            response shouldHaveStatus HttpStatusCode.OK
        }
    }
}

fun includePrefixes(vararg prefixKeys: String, extraSetup: (PrefixMapBuilder.() -> Unit)?=null): String {
    return PrefixMapBuilder().apply {
        add(*SPARQL_PREFIXES.map.filterKeys {
            it in prefixKeys
        }.toList().toTypedArray())

        extraSetup?.invoke(this)
    }.toString()
}

fun includeAllTestPrefixes(extraSetup: (PrefixMapBuilder.() -> Unit)?=null): String {
    return includePrefixes(
        "rdf",
        "rdfs",
        "dct",
        "xsd",
        "mms",
        "m",
        "m-graph",
    ) {
        add(
            "foaf" to "http://xmlns.com/foaf/0.1/"
        )

        extraSetup?.invoke(this)
    }
}

fun withAllTestPrefixes(body: String): String {
    return """
        ${includeAllTestPrefixes()}
        
        $body
    """.trimIndent()
}

suspend fun addDummyTransaction(updateUrl: String, branchPath: String) {
    val client = HttpClient()
    client.post(updateUrl) {
        contentType(ContentType.Application.FormUrlEncoded)
        parameter("update", """
             prefix m-graph: <$ROOT_CONTEXT/graphs/>
             prefix mms: <https://mms.openmbee.org/rdf/ontology/>
             prefix mt: <$ROOT_CONTEXT/transactions/some-other-transaction> 
             prefix morb: <$ROOT_CONTEXT$branchPath> 
             insert data {
                 graph m-graph:Transactions {
                     mt: a mms:Transaction ;
                         mms:branch morb: .
                 }
             }
        """.trimIndent())
    }
}
