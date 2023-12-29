package org.openmbee.flexo.mms.util


import io.kotest.assertions.fail
import io.kotest.assertions.json.shouldBeJsonObject
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.*
import org.openmbee.flexo.mms.KModel
import org.openmbee.flexo.mms.RdfContentTypes
import org.openmbee.flexo.mms.parseTurtle
import org.openmbee.flexo.mms.reindent


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
        val subject = subjectContext.subject

        // assert the statements existence and then remove it from the model
        subjectContext.assertAndRemove(property, node)

        // assert no other statements have the same subject and predicate
        val others = subject.listProperties(property)
        if(others.hasNext()) {
            fail("\"${subjectContext.modelName}\" model has extraneous triples on subject/predicate <${subject}>/<$property>:\n"
                    +others.toList().joinToString("\n") { "$it" })
        }
    }
}


/**
 * Requires a predicate/object pair match exactly
 */
class ExactPairSetPattern(private val property: Property, private val nodes: List<RDFNode>): PairPattern() {
    override fun evaluate(subjectContext: SubjectContext) {
        val subject = subjectContext.subject

        // first check that the actual size matches the expected size
        val actual = subjectContext.cardinality(property, nodes.size)

        // assert that the sets are equivalent
        if(nodes.toSet() != actual.toSet()) {
            fail("\"${subjectContext.modelName}\" model at <${subject}>/<$property> contains a set of terms that do not match the expected values:\n"
                    +"expected: "+nodes.joinToString(", ") { "<${it}>" }
                    +"actual: "+actual.joinToString(",") { "$it" })
        }

        // remove all statements belonging to this property
        subject.removeAll(property)
    }
}


/**
 * Requires a predicate/object pair match exactly
 */
class DatatypePairPattern(private val property: Property, private val datatype: Resource): PairPattern() {
    override fun evaluate(subjectContext: SubjectContext) {
        val subject = subjectContext.subject

        // assert the statements existence and then remove it from the model
        subjectContext.assertDatatype(property, datatype)

        // assert no other statements have the same subject and predicate
        val others = subject.listProperties(property)
        if(others.hasNext()) {
            fail("\"${subjectContext.modelName}\" model has extraneous triples on subject/predicate <$subject>/<$property>:\n"
                    +others.toList().joinToString("\n") { "$it" })
        }
    }
}


/**
 * Requires a predicate/object pair match given the object starts with the specified value
 */
class StartsWithPairPattern(private val property: Property, private val node: RDFNode): PairPattern() {
    override fun evaluate(subjectContext: SubjectContext) {
        val subject = subjectContext.subject

        // assert cardinality and fetch objects
        val nodes = subjectContext.cardinality(property, 1)
        val node0 = nodes[0]

        if(node.isURIResource) {
            if(!node0.isURIResource) {
                fail("Expected object at <${subject}>/<$property> to be an URI resource")
            }

            node0.asResource().uri shouldStartWith node.asResource().uri
        }
        else {
            if(!node0.isLiteral) {
                fail("Expected object at <${subject}>/<$property> to be a literal")
            }

            node0.asLiteral().string shouldStartWith node.asLiteral().string
        }

        // remove statement from model
        subjectContext.model.remove(subject, property, node0)
    }
}


infix fun Property.exactly(node: RDFNode): PairPattern {
    return ExactPairPattern(this, node)
}

infix fun Property.exactly(string: String): PairPattern {
    return ExactPairPattern(this, ResourceFactory.createStringLiteral(string))
}

infix fun Property.exactly(nodes: List<RDFNode>): PairPattern {
    return ExactPairSetPattern(this, nodes)
}

infix fun Property.hasDatatype(datatype: Resource): PairPattern {
    return DatatypePairPattern(this, datatype)
}

infix fun Property.startsWith(node: RDFNode): PairPattern {
    return StartsWithPairPattern(this, node)
}

infix fun Property.startsWith(value: String): PairPattern {
    return StartsWithPairPattern(this, ResourceFactory.createStringLiteral(value))
}

/**
 * Adds context to a Model
 */
data class ModelContext(val model: Model, val modelName: String)

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
            fail("\"$modelName\" model has extraneous triples on subject $subject:\n"+others.toList().joinToString("\n") { "$it" })
        }
    }

    fun cardinality(property: Property, expectedSize: Int): List<RDFNode> {
        // fetch all statements with this property
        val nodes = model.listObjectsOfProperty(subject, property).toList()

        // under target
        if(nodes.size < expectedSize) {
            withClue("\"$modelName\" model is missing triple(s) for subject/predicate <$subject>/<$property>") {
                nodes.size shouldBe expectedSize
            }
        }
        // over target
        else if(nodes.size > expectedSize) {
            var reason: String = "has too many"

            // should be empty
            if(expectedSize == 0) {
                reason = "should NOT have any"
            }

            withClue("\"$modelName\" model $reason triple(s) for subject/predicate <$subject>/<$property>") {
                nodes.size shouldBe expectedSize
            }
        }

        return nodes
    }

    fun assertDatatype(property: Property, datatype: Resource, expectedCardinality: Int=1) {
        // assert cardinality and fetch objects
        val nodes = cardinality(property, expectedCardinality)

        // assert expected value (if there are extra, this will catch them)
        nodes.forEach { it.asLiteral().datatype.uri shouldBe datatype.uri }

        // remove statements from model
        model.removeAll(subject, property, null)
    }

    /**
     * Asserts the given statement exists and then removes it
     */
    fun assertAndRemove(property: Property, obj: RDFNode, expectedCardinality: Int=1) {
        // assert cardinality and fetch objects
        val nodes = cardinality(property, expectedCardinality)

        // assert expected value (if there are extra, this will catch them)
        nodes.forEach { it shouldBe obj }

        // remove statement from model
        model.remove(subject, property, obj)
    }

    /**
     * Removes all the remaining triples about this subject
     */
    fun removeRest() {
        model.removeAll(subject, null, null)
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

    fun includes(vararg pattern: PairPattern?) {
        // assert and remove each statement
        pattern.forEach {
            it?.evaluate(subjectContext)
        }

        // remove the rest
        subjectContext.removeRest()
    }

    fun ignoreAll() {
        subjectContext.removeRest()
    }
}


class TriplesAsserter(val model: Model, var modelName: String="Unnamed") {

    /**
     * Asserts that no other statements exist in the model
     */
    fun assertEmpty() {
        val others = model.listStatements()
        if(others.hasNext()) {
            fail("\"$modelName\" model has extraneous triples:\n"+others.toList().joinToString("\n") { "$it" })
        }
    }

    /**
     * Asserts the given subject by IRI exists and asserts its contents
     */
    fun optionalSubject(iri: String, assertions: SubjectHandle.() -> Unit) {
        // create resource
        val subject = model.createResource(iri)

        // check for existence
        val exists = model.contains(subject, null)
        if(!exists) return

        // apply assertions
        SubjectHandle(ModelContext(model, modelName), subject).apply { assertions() }
    }


    /**
     * Asserts the given subject by IRI exists and asserts its contents
     */
    fun subject(iri: String, assertions: SubjectHandle.() -> Unit) {
        // create resource
        val subject = model.createResource(iri)

        // check for existence
        val exists = model.contains(subject, null)
        if(!exists) fail("No triples were found in the \"$modelName\" model having subject <$iri>\n\"\"\"${KModel.fromModel(model).stringify().reindent(1)}\n\"\"\"")

        // apply assertions
        SubjectHandle(ModelContext(model, modelName), subject).apply { assertions() }
    }

    /**
     * Asserts the given subject by terse string exists and asserts its contents
     */
    fun subjectTerse(terse: String, assertions: SubjectHandle.() -> Unit) {
        // extract prefix id
        val prefixId = terse.substringBefore(':')

        // locate it and assert it exists
        val prefixIri = model.getNsPrefixURI(prefixId)
            ?: fail("Expected the '$prefixId:' prefix to be set on the parse model but it was missing")

        // expand and carry on
        return this.subject(model.expandPrefix(terse), assertions)
    }

    /**
     * Asserts that exactly one subject's IRI starts with the given string and asserts its contents
     */
    fun matchMultipleSubjectsByPrefix(prefix: String, assertions: SubjectHandle.() -> Unit) {
        // find matching subjects
        val matches = model.listSubjects().filterKeep { it?.uri?.startsWith(prefix)?: false }.toList()

        for(match in matches) {
            this.subject(match.uri, assertions)
        }
    }

    /**
     * Asserts that exactly one subject's IRI starts with the given string and asserts its contents
     */
    fun matchOneSubjectByPrefix(iri: String, assertions: SubjectHandle.() -> Unit) {
        // find matching subjects
        val matches = model.listSubjects().filterKeep { it?.uri?.startsWith(iri)?: false }.toList()

        withClue("Expected exactly one subject to start with \"$iri\"") {
            matches.size shouldBe 1
        }

        return this.subject(matches[0]!!.uri, assertions)
    }

    /**
     * Asserts the given subject by terse string exists and asserts its contents
     */
    fun matchOneSubjectTerseByPrefix(terse: String, assertions: SubjectHandle.() -> Unit) {
        // extract prefix id
        val prefixId = terse.substringBefore(':')

        // locate it and assert it exists
        val prefixIri = model.getNsPrefixURI(prefixId)
            ?: fail("Expected the '$prefixId:' prefix to be set on the parse model but it was missing")

        // expand and carry on
        return this.matchOneSubjectByPrefix(model.expandPrefix(terse), assertions)
    }
}

fun TestApplicationResponse.includesTriples(statusCode: HttpStatusCode, assertions: TriplesAsserter.() -> Unit): TriplesAsserter {
    this shouldHaveStatus statusCode

    // assert content-type header (ignore charset if present)
    this.headers[HttpHeaders.ContentType].shouldStartWith(RdfContentTypes.Turtle.contentType)

    // parse turtle into model
    val model = ModelFactory.createDefaultModel()
    parseTurtle(this.content.toString(), model, this.call.request.uri)

    // make triple assertions and then assert the model is empty
    return TriplesAsserter(model).apply { assertions() }
}

infix fun TestApplicationResponse.includesTriples(assertions: TriplesAsserter.() -> Unit): TriplesAsserter {
    return includesTriples(HttpStatusCode.OK, assertions)
}

fun TestApplicationResponse.exclusivelyHasTriples(statusCode: HttpStatusCode, assertions: TriplesAsserter.() -> Unit) {
    includesTriples(statusCode, assertions).assertEmpty()
}

infix fun TestApplicationResponse.exclusivelyHasTriples(assertions: TriplesAsserter.() -> Unit) {
    exclusivelyHasTriples(HttpStatusCode.OK, assertions)
}

infix fun TestApplicationResponse.shouldEqualSparqlResultsJson(expectedJson: String) {
    // 200
    this shouldHaveStatus HttpStatusCode.OK

    // assert content-type header (ignore charset if present)
    this.headers[HttpHeaders.ContentType].shouldStartWith(RdfContentTypes.SparqlResultsJson.contentType)

    // json object
    this.content!!.shouldBeJsonObject()

    // assert equal
    this.content!!.shouldEqualJson(expectedJson)
}
