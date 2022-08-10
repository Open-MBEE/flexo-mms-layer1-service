package org.openmbee.mms5

import io.kotest.core.test.TestCase
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.mms5.util.*

fun title(name: String): String {
    return "<> dct:title \"$name\"@en .\n"
}

open class RefAny : RepoAny() {
    val branchId = "new-branch"
    val branchName = "New Branch"
    val branchPath = "$repoPath/branches/$branchId"
    val masterPath = "$repoPath/branches/master"

    val lockId = "new-lock"
    val lockPath = "$repoPath/locks/$lockId"

    val fromMaster = "<> mms:ref <../branches/master> .\n"

    val validBranchBodyFromMaster = title(branchName)+fromMaster

    var repoEtag = ""

    // create a repo before each branch test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        repoEtag = createRepo(orgPath, repoId, repoName).response.headers[HttpHeaders.ETag]!!
    }

    fun TestApplicationCall.validateCreateBranchResponse(fromCommit: String) {
        response shouldHaveStatus HttpStatusCode.OK
        response.headers[HttpHeaders.ETag].shouldNotBeBlank()

        response exclusivelyHasTriples {
            modelName = "CreateBranch"

            subject(localIri(branchPath)) {
                includes(
                    RDF.type exactly MMS.Branch,
                    MMS.id exactly branchId,
                    DCTerms.title exactly branchName.en,
                    MMS.etag exactly response.headers[HttpHeaders.ETag]!!,
                    MMS.commit startsWith localIri("$commitsPath/").iri,
                    MMS.createdBy exactly userIri("root").iri
                )
            }

            matchOneSubjectTerseByPrefix("m-policy:AutoBranchOwner") {
                includes(
                    RDF.type exactly MMS.Policy,
                )
            }

            subjectTerse("mt:") {
                includes(
                    RDF.type exactly MMS.Transaction,
                    MMS.created hasDatatype XSD.dateTime,
                    MMS.org exactly localIri(orgPath).iri,
                    MMS.repo exactly localIri(repoPath).iri,
                    MMS.branch exactly localIri(branchPath).iri,
                    MMS.user exactly userIri("root").iri
                )
            }

            // inspect
            subject("urn:mms:inspect") { ignoreAll() }
        }
    }
}
