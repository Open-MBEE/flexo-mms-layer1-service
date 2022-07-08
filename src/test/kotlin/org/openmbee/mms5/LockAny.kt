package org.openmbee.mms5

import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.mms5.util.TriplesAsserter
import org.openmbee.mms5.util.exactly
import org.openmbee.mms5.util.hasDatatype
import org.openmbee.mms5.util.iri

fun TriplesAsserter.validateLockTriples(
    lockId: String,
    etag: String,
    orgPath: String,
) {
    // lock triples
    subjectTerse("mor:locks/$lockId") {
        exclusivelyHas(
            RDF.type exactly MMS.Lock,
            MMS.id exactly lockId,
            MMS.etag exactly etag,
            MMS.commit exactly model.expandPrefix("morc").iri,
            MMS.createdBy exactly model.expandPrefix("mu").iri,
        )
    }

    // auto policy
    matchOneSubjectTerseByPrefix("m-policy:AutoLockOwner") {
        includes(
            RDF.type exactly MMS.Policy,
        )
    }

    // transaction
    subjectTerse("mt:") {
        includes(
            RDF.type exactly MMS.Transaction,
            MMS.created hasDatatype XSD.dateTime,
            MMS.org exactly orgPath.iri,
        )
    }

    // inspect
    subject("urn:mms:inspect") { ignoreAll() }
}


open class LockAny : RefAny() {

}