package org.openmbee.flexo.mms

import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.util.*

open class ModelAny: RefAny() {

    fun TriplesAsserter.validateModelCommitResponse(
        branchPath: String,
        etag: String,
    ) {
        matchOneSubjectByPrefix(localIri("$demoCommitsPath/")) {
            includes(
                RDF.type exactly MMS.Commit,
                MMS.submitted hasDatatype XSD.dateTime,
                MMS.parent startsWith localIri("$demoCommitsPath/").iri,
                MMS.data startsWith localIri("$demoCommitsPath/").iri,
                MMS.createdBy exactly localIri("/users/root").iri
            )
        }
        /*
        //currently it returns AutoOrgOwner,
        matchOneSubjectTerseByPrefix("m-policy:AutoOrgOwner") {
            includes(
                RDF.type exactly MMS.Policy,
            )
        }*/

        // validate transaction
        validateTransaction(orgPath=demoOrgPath, repoPath=demoRepoPath, branchPath=branchPath, user="root")

        // inspect
        subject("urn:mms:inspect") { ignoreAll() }
    }
}
