package org.openmbee.mms5.util


import io.kotest.assertions.fail
import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.withClue
import io.kotest.matchers.iterator.shouldHaveNext
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.*
import org.openmbee.mms5.RdfContentTypes
import org.openmbee.mms5.parseTurtle


/**
 * Converts a string into a jena Resource
 */
var String.iri: Resource
    get() = ResourceFactory.createResource(this)
    set(v) {}


/**
 * Converts a string into a jena English Language Literal
 */
var String.en: Literal
    get() = ResourceFactory.createLangLiteral(this, "en")
    set(v) {}


/**
 * Converts a string into a jena Property
 */
var String.toPredicate: Property
    get() = ResourceFactory.createProperty(this)
    set(v) {}


/**
 * Abstract class for a predicate/object pair pattern
 */
abstract class PairPattern {
    abstract fun evaluate(subjectContext: SubjectContext)
}

/**
 * Requires a predicate/object pair match exactly
 */
class ExactPairPattern(private val property: Property, private val node: RDFNode): PairPattern() {
    override fun evaluate(subjectContext: SubjectContext) {
        // assert the statements existence and then remove it from the model
        subjectContext.assertAndRemove(property, node)

        // assert no other statements have the same subject and predicate
        val others = subjectContext.subject.listProperties(property)
        if(others.hasNext()) {
            fail("${subjectContext.modelName} model has extraneous triples on subject/predicate ${subjectContext.subject}/$property:\n"
                    +others.toList().joinToString("\n") { "$it" })
        }
    }
}


infix fun Property.exactly(node: RDFNode): PairPattern {
    return ExactPairPattern(this, node)
}

infix fun Property.exactly(string: String): PairPattern {
    return ExactPairPattern(this, ResourceFactory.createStringLiteral(string))
}


/**
 * Adds context to a Model
 */
class ModelContext(val model: Model, val modelName: String) {
    /**
     * Asserts that no other statements exist in the model
     */
    fun assertEmpty() {
        val others = model.listStatements()
        if(others.hasNext()) {
            fail("$modelName model has extraneous triples:\n"+others.toList().joinToString("\n") { "$it" })
        }
    }
}

/**
 * Adds contexts to a subject Resource in some Model
 */
class SubjectContext(modelContext: ModelContext, val subject: Resource) {
    val model = modelContext.model
    val modelName = modelContext.modelName

    /**
     * Asserts no other statements exist with this subject
     */
    fun assertEmpty() {
        val others = subject.listProperties()
        if(others.hasNext()) {
            fail("$modelName model has extraneous triples on subject $subject:\n"+others.toList().joinToString("\n") { "$it" })
        }
    }

    /**
     * Asserts the given statement exists and then removes it
     */
    fun assertAndRemove(property: Property, obj: RDFNode) {
        // create expected statement
        val expected = model.createStatement(subject, property, obj)

        // fetch all statements with this property
        val stmts = subject.listProperties(property)

        // missing triple
        withClue("$modelName model should have triple(s) for subject $subject") {
            stmts.shouldHaveNext()
        }

        // assert expected value
        stmts.forEach { stmt ->
            stmt shouldBe expected
        }

        // // check for existence
        // val exists = model.contains(stmt)
        // if(!exists) {
        //     stmt.toString().asClue {
        //     }
        //     fail("$modelName model missing required triple: $stmt")
        // }

        // remove statement from model
        model.remove(expected)
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

infix fun TestApplicationResponse.exclusivelyHasTriples(assertions: TriplesAsserter.() -> Unit) {
    // assert content-type header
    this.shouldHaveHeader(HttpHeaders.ContentType, RdfContentTypes.Turtle.toString())

    // parse turtle into model
    val model = ModelFactory.createDefaultModel()
    parseTurtle(this.content.toString(), model, this.call.request.uri)

    TriplesAsserter(model).apply { assertions() }
}
