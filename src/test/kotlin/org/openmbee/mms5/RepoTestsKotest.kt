package org.openmbee.mms5

import io.kotest.assertions.asClue
import io.kotest.assertions.fail
import io.ktor.http.*
import io.kotest.assertions.ktor.*
import io.kotest.assertions.withClue
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.iterator.shouldHaveNext
import io.kotest.matchers.iterator.shouldNotHaveNext
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.request.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.openmbee.mms5.util.AuthObject
import org.openmbee.mms5.util.TestBase
import kotlin.math.exp


var String.iri: Resource
    get() = ResourceFactory.createResource(this)
    set(value) {}

var String.en: Literal
    get() = ResourceFactory.createLangLiteral(this, "en")
    set(value) {}


abstract class PairPattern {
    abstract fun evaluate(subjectContext: SubjectContext)
}

class ExactPairPattern(private val property: Property, private val node: RDFNode): PairPattern() {
    override fun evaluate(subjectContext: SubjectContext) {
        // assert the statements existence and then remove it from the model
        subjectContext.assertAndRemove(property, node)

        // assert no other statements have the same subject and predicate
        withClue("${subjectContext.modelName} model should not have other triples for subject ${subjectContext.subject}") {
            subjectContext.subject.listProperties().shouldNotHaveNext()
        }
    }
}


infix fun Property.exactly(node: RDFNode): PairPattern {
    return ExactPairPattern(this, node)
}

infix fun Property.exactly(string: String): PairPattern {
    return ExactPairPattern(this, ResourceFactory.createStringLiteral(string))
}

fun P(iri: String): Property {
    return ResourceFactory.createProperty(iri)
}


data class ModelContext(val model: Model, val modelName: String)

class SubjectContext(modelContext: ModelContext, val subject: Resource) {
    val model = modelContext.model
    val modelName = modelContext.modelName

    fun assertAndRemove(property: Property, obj: RDFNode) {
        // create expected statement
        val expected = model.createStatement(subject, property, obj)

        // fetch all statements with this property
        val stmts = subject.listProperties(property)

        // missing triple
        withClue("$modelName model should have triple for subject $subject") {
            stmts.shouldHaveNext()
        }

        //
        stmts.forEach { stmt ->
            stmt shouldBe expected
        }
        //
        // // check for existence
        // val exists = model.contains(stmt)
        // if(!exists) {
        //     stmt.toString().asClue {
        //     }
        //     fail("$modelName model missing required triple: $stmt")
        // }

        // remove from model
        model.remove(expected)
    }

    fun assertEmpty() {
        // assert no other statements exist
        val others = model.listStatements()
        if(others.hasNext()) {
            fail("$modelName model has extra triples"+others.toList().joinToString("\n") { "$it" })
        }
    }
}


class SubjectHandle(modelContext: ModelContext, subject: Resource) {
    private val subjectContext = SubjectContext(modelContext, subject)

    fun exclusivelyHas(vararg pattern: PairPattern) {
        // assert and remove each statement
        pattern.forEach {
            it.evaluate(subjectContext)
        }

        // no other statements should exist
        subjectContext.assertEmpty()
    }
}


class TriplesAsserter(val model: Model, var modelName: String="Unnamed") {
    fun subject(iri: String, assertions: SubjectHandle.() -> Unit) {
        // create resource
        val subject = model.createResource(iri)

        // check for existence
        val exists = model.contains(subject, null)
        if(!exists) fail("No triples were found in the $modelName model having subject <$iri>")

        // apply assertions
        SubjectHandle(ModelContext(model, modelName), subject).apply { assertions() }
    }
}



infix fun TestApplicationResponse.shouldHaveTriples(assertions: TriplesAsserter.() -> Unit) = this should haveTriples(assertions)
fun haveTriples(assertions: TriplesAsserter.() -> Unit) = object : Matcher<TestApplicationResponse> {
    override fun test(response: TestApplicationResponse): MatcherResult {
        // assert content-type header
        response.shouldHaveHeader(HttpHeaders.ContentType, RdfContentTypes.Turtle.toString())

        // parse turtle into model
        val model = ModelFactory.createDefaultModel()
        parseTurtle(response.content.toString(), model, response.call.request.uri)

        TriplesAsserter(model).apply { assertions() }
    }
}

fun localIri(suffix: String): String {
    return "$suffix"
}

class RepoTestsKotest : TestBase() {

    private val defaultAuthObject = AuthObject(
        username = "root",
        groups = listOf("super_admins")
    )

    private val orgId = "repoTests"
    private val orgPath = "/orgs/$orgId"
    private val orgName = "Repo Tests"

    @Test
    @Order(1)
    fun createOnNewOrg() {
        doCreateOrg(defaultAuthObject, orgId, orgName)

        val repoId = "new-repo"
        val repoName = "New Repo"
        val repoPath = "$orgPath/repos/$repoId"

        val arbitraryPropertyIri = "https://demo.org/custom/prop"
        val arbitraryPropertyValue = "test"

        withTestEnvironment {
            handleRequest(HttpMethod.Put, repoPath) {
                addAuthorizationHeader(defaultAuthObject)
                setTurtleBody("""
                    <>
                        dct:title "$repoName"@en ;
                        <$arbitraryPropertyIri> "$arbitraryPropertyValue" ;
                        .
                """.trimIndent())
            }.apply {
                response shouldHaveStatus HttpStatusCode.OK
                response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                response shouldHaveTriples {
                    modelName = "response"

                    subject(localIri(repoPath)) {
                        exclusivelyHas(
                            RDF.type exactly MMS.Repo,
                            MMS.id exactly repoId,
                            MMS.org exactly localIri(orgPath).iri,
                            DCTerms.title exactly repoName.en,
                            MMS.etag exactly response.headers[HttpHeaders.ETag]!!,
                            P(arbitraryPropertyIri) exactly arbitraryPropertyValue,
                        )
                    }
                }
            }
        }
    }
}