package org.openmbee.mms5

import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.mms5.util.*


@JvmOverloads
fun TriplesAsserter.validateTransaction(orgPath: String?=null, repoPath: String?=null, branchPath: String?=null, user: String?=null) {
    subjectTerse("mt:") {
        includes(
            RDF.type exactly  MMS.Transaction,
            MMS.created hasDatatype  XSD.dateTime,
            if(orgPath != null) MMS.org exactly localIri(orgPath).iri else null,
            if(repoPath != null) MMS.repo exactly localIri(repoPath).iri else null,
            if(branchPath != null) MMS.branch exactly localIri(branchPath).iri else null,
            if(user != null) MMS.user exactly userIri("root").iri else null
        )
    }
}
