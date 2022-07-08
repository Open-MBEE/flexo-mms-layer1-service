package org.openmbee.mms5

import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.MMS.etag
import org.openmbee.mms5.util.TriplesAsserter
import org.openmbee.mms5.util.exactly
import org.openmbee.mms5.util.iri

fun TriplesAsserter.validateLockTriples(lockId: String, etag: String) {
    subjectTerse("mor:locks/$lockId") {
        exclusivelyHas(
            RDF.type exactly MMS.Lock,
            MMS.id exactly lockId,
            MMS.etag exactly etag!!,
            MMS.commit exactly model.expandPrefix("morc").iri,
            MMS.createdBy exactly model.expandPrefix("mu").iri,
        )
    }
}

open class LockAny : RefAny() {

}