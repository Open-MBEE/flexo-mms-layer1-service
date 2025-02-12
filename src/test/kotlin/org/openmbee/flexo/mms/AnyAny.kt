package org.openmbee.flexo.mms

import org.apache.jena.rdf.model.Statement
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.util.*


@JvmOverloads
fun TriplesAsserter.validateTransaction(orgPath: String?=null, repoPath: String?=null, branchPath: String?=null, user: String?=null) {
    val linkedStatements = mutableSetOf<Statement>()

    subjectTerse("mt:") {
        linkedStatements.addAll(subject.listProperties(MMS.appliedPolicy).toList())
        linkedStatements.addAll(subject.listProperties(MMS.createdPolicy).toList())

        includes(
            RDF.type exactly  MMS.Transaction,
            MMS.created hasDatatype  XSD.dateTime,
            if(orgPath != null) MMS.org exactly localIri(orgPath).iri else null,
            if(repoPath != null) MMS.repo exactly localIri(repoPath).iri else null,
            if(branchPath != null) MMS.branch exactly localIri(branchPath).iri else null,
            if(user != null) MMS.user exactly userIri("root").iri else null
        )
    }

    val linkedResources = linkedStatements.map { it.`object` }

    for(resource in linkedResources) {
        subject(resource.asResource().uri) {
            ignoreAll()
        }
    }
}
