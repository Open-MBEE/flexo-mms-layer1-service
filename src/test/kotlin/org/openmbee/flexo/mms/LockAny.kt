package org.openmbee.flexo.mms

import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory

// validates response triples for a lock
fun TriplesAsserter.validateLockTriples(
    lockId: String,
    etag: String?=null,
    extraPatterns: List<PairPattern> = listOf()
) {
    // lock triples
    subjectTerse("mor-lock:$lockId") {
        exclusivelyHas(
            RDF.type exactly MMS.Lock,
            MMS.id exactly lockId,
            if(etag != null) MMS.etag exactly etag else MMS.etag startsWith "",
            MMS.commit startsWith model.expandPrefix("mor-commit:").iri,
            MMS.snapshot startsWith model.expandPrefix("mor-snapshot:Model.").iri,
            MMS.createdBy exactly model.expandPrefix("mu:").iri,
            *extraPatterns.toTypedArray(),
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

    // transaction
    validateTransaction(orgPath=orgPath)

    // inspect
    subject(MMS_URNS.SUBJECT.inspect) { ignoreAll() }
}

open class LockAny : RefAny() {
    override val logger = LoggerFactory.getLogger(LockAny::class.java)

    val insertLock = """
        insert data {
            <urn:mms:s> <urn:mms:p> <urn:mms:o> .
        }
    """.trimIndent()


}
