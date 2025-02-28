package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory

fun TriplesAsserter.validateCommitTriples(
    commitsPath: String,
    commitIri: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    // commit triples
    subject(commitIri) {
        includes(
            RDF.type exactly MMS.Commit,
            MMS.submitted hasDatatype XSD.dateTime,
            MMS.createdBy exactly localIri("/users/root").iri,
            MMS.parent startsWith localIri("$commitsPath/").iri,
            MMS.data startsWith localIri("$commitsPath/").iri,
            MMS.etag startsWith "",
            *extraPatterns.toTypedArray()
        )
    }

    // inspections
    optionalSubject(MMS_URNS.SUBJECT.inspect) { ignoreAll() }

    // context
    optionalSubject(MMS_URNS.SUBJECT.context) {
        // remove linked policy triples
        subject.listProperties(MMS.appliedPolicy).forEach {
            optionalSubject(it.`object`.asResource().uri) { ignoreAll() }
        }
        ignoreAll()
    }
}
open class CommitAny : RefAny() {
    val basePathCommits = "$demoRepoPath/commits"
    override val logger = LoggerFactory.getLogger(CommitAny::class.java)
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
    }
}
