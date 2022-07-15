package org.openmbee.mms5

import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.mms5.util.*

fun TriplesAsserter.validateLockTriples(
    lockId: String,
    etag: String,
    orgPath: String,
) {
    // lock triples
    subjectTerse("mor-lock:$lockId") {
        exclusivelyHas(
            RDF.type exactly MMS.Lock,
            MMS.id exactly lockId,
            MMS.etag exactly etag,
            MMS.commit startsWith model.expandPrefix("mor:/commits/").iri,
            MMS.createdBy exactly model.expandPrefix("mu:").iri,
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
            MMS.org exactly localIri(orgPath).iri,
        )
    }

    // inspect
    subject("urn:mms:inspect") { ignoreAll() }
}


open class LockAny : RefAny() {

}