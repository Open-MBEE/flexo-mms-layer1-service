package org.openmbee.mms5.util

import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.mms5.*


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

fun includePrefixes(vararg prefixKeys: String, extraSetup: (PrefixMapBuilder.() -> Unit)?=null): String {
    return PrefixMapBuilder().apply {
        add(*SPARQL_PREFIXES.map.filterKeys {
            it in prefixKeys
        }.toList().toTypedArray())

        extraSetup?.invoke(this)
    }.toString()
}

fun includeAllTestPrefixes(extraSetup: (PrefixMapBuilder.() -> Unit)?=null): String {
    return includePrefixes("rdf", "rdfs", "dct", "xsd", "mms") {
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
