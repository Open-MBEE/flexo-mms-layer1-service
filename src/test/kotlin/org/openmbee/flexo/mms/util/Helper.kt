package org.openmbee.flexo.mms.util

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.*
import java.net.URLEncoder

suspend fun ApplicationTestBuilder.createOrg(orgId: String, orgName: String): HttpResponse {
    val response = httpPut("/orgs/$orgId") {
        setTurtleBody("""
            <> dct:title "$orgName"@en .
        """.trimIndent())
    }
    response shouldHaveStatus HttpStatusCode.Created
    return response
}

suspend fun ApplicationTestBuilder.createRepo(orgPath: String, repoId: String, repoName: String): HttpResponse {
    val response = httpPut("$orgPath/repos/$repoId") {
        setTurtleBody("""
            <> dct:title "$repoName"@en .
        """.trimIndent())
    }
    response shouldHaveStatus HttpStatusCode.Created
    return response
}

suspend fun ApplicationTestBuilder.createScratch(path: String, scratchName: String): HttpResponse {
    val response = httpPut(path) {
        setTurtleBody("""
            <> dct:title "$scratchName"@en .
        """.trimIndent())
    }
    response shouldHaveStatus HttpStatusCode.Created
    return response
}

suspend fun ApplicationTestBuilder.createBranch(repoPath: String, refId: String, branchId: String, branchName: String): HttpResponse {
    val response = httpPut("$repoPath/branches/$branchId") {
        setTurtleBody("""
            <> dct:title "$branchName"@en .
            <> mms:ref <./$refId> .
        """.trimIndent())
    }
    response shouldHaveStatus HttpStatusCode.Created
    return response
}

suspend fun ApplicationTestBuilder.createLock(repoPath: String, refPath: String, lockId: String): HttpResponse {
    val response = httpPut("$repoPath/locks/$lockId") {
        setTurtleBody("""
           <> mms:ref <$refPath> .
        """.trimIndent())
    }
    response shouldHaveStatus HttpStatusCode.Created
    return response
}

suspend fun ApplicationTestBuilder.createGroup(groupId: String, groupTitle: String): HttpResponse {
    val response = httpPut("/groups/${URLEncoder.encode(groupId, "UTF-8")}") {
        setTurtleBody("""
           <> dct:title "${groupTitle}"@en .
        """.trimIndent())
    }
    response shouldHaveStatus HttpStatusCode.Created
    return response
}

suspend fun ApplicationTestBuilder.updateScratch(scratchPath: String, sparql: String): HttpResponse {
    val response = httpPost("$scratchPath/update") {
        setSparqlUpdateBody(sparql)
    }
    response shouldHaveStatus HttpStatusCode.OK
    return response
}

suspend fun ApplicationTestBuilder.commitModel(refPath: String, sparql: String): HttpResponse {
    val response = httpPost("$refPath/update") {
        setSparqlUpdateBody(sparql)
    }
    response shouldHaveStatus HttpStatusCode.Created
    return response
}

suspend fun ApplicationTestBuilder.loadModel(refPath: String, turtle: String): HttpResponse {
    val response = httpPut("$refPath/graph") {
        setTurtleBody(turtle)
    }
    response shouldHaveStatus HttpStatusCode.OK
    return response
}

suspend fun ApplicationTestBuilder.loadScratch(scratchPath: String, turtle: String): HttpResponse {
    val response = httpPut("$scratchPath/graph") {
        setTurtleBody(turtle)
    }
    response shouldHaveStatus HttpStatusCode.NoContent
    return response
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
             prefix mms-txn: <https://mms.openmbee.org/rdf/ontology/txn.>
             prefix morb: <$ROOT_CONTEXT$branchPath> 
             insert data {
                 graph m-graph:Transactions {
                     mt: a mms:Transaction ;
                         mms-txn:mutex morb: .
                 }
             }
        """.trimIndent())
    }
}
