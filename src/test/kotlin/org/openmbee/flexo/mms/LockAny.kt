package org.openmbee.flexo.mms

import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.TriplesAsserter
import org.openmbee.flexo.mms.util.exactly
import org.openmbee.flexo.mms.util.iri
import org.openmbee.flexo.mms.util.startsWith
import org.slf4j.LoggerFactory

// validates response triples for a lock
fun TriplesAsserter.validateLockTriples(lockId: String, etag: String) {
    // lock triples
    subjectTerse("mor-lock:$lockId") {
        exclusivelyHas(
            RDF.type exactly MMS.Lock,
            MMS.id exactly lockId,
            MMS.etag exactly etag,
            MMS.commit startsWith model.expandPrefix("mor-commit:").iri,
            MMS.snapshot startsWith model.expandPrefix("mor-snapshot:Model.").iri,
            MMS.createdBy exactly model.expandPrefix("mu:").iri,
        )
    }
}

// validates response triples for a newly created lock
fun TriplesAsserter.validateCreatedLockTriples(
    lockId: String,
    etag: String,
    orgPath: String,
) {
    validateLockTriples(lockId, etag)

    // auto policy
    matchOneSubjectTerseByPrefix("m-policy:AutoLockOwner") {
        includes(
            RDF.type exactly MMS.Policy,
        )
    }

    // transaction
    validateTransaction(orgPath=orgPath)

    // inspect
    subject(MMS_URNS.SUBJECT.inspect) { ignoreAll() }
}

open class LockAny : RefAny() {
    override val logger = LoggerFactory.getLogger(RepoAny::class.java)

    val insertLock = """
        insert data {
            <urn:mms:s> <urn:mms:p> <urn:mms:o> .
        }
    """.trimIndent()


}