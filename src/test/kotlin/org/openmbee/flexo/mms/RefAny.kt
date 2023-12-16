package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*

fun title(name: String): String {
    return "<> dct:title \"$name\"@en .\n"
}

open class RefAny : RepoAny() {
    val branchId = "new-branch"
    val branchName = "New Branch"
    val branchPath = "$demoRepoPath/branches/$branchId"
    val masterPath = "$demoRepoPath/branches/master"

    val lockId = "new-lock"
    val lockPath = "$demoRepoPath/locks/$lockId"

    val fromMaster = "<> mms:ref <../branches/master> .\n"

    val validBranchBodyFromMaster = title(branchName)+fromMaster

    var repoEtag = ""

    // create a repo before each branch test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        repoEtag = createRepo(demoOrgPath, demoRepoId, demoRepoName).response.headers[HttpHeaders.ETag]!!
    }

    fun TestApplicationCall.validateCreateBranchResponse(fromCommit: String) {
        response.headers[HttpHeaders.ETag].shouldNotBeBlank()

        response exclusivelyHasTriples {
            modelName = "CreateBranch"

            subject(localIri(branchPath)) {
                includes(
                    RDF.type exactly MMS.Branch,
                    MMS.id exactly branchId,
                    DCTerms.title exactly branchName.en,
                    MMS.etag exactly response.headers[HttpHeaders.ETag]!!,
                    MMS.commit startsWith localIri("$demoCommitsPath/").iri,  // TODO: incorporate fromCommit ?
                    MMS.createdBy exactly userIri("root").iri
                )
            }

            matchOneSubjectTerseByPrefix("m-policy:AutoBranchOwner") {
                includes(
                    RDF.type exactly MMS.Policy,
                )
            }

            validateTransaction(demoOrgPath, demoRepoPath, branchPath, "root")

            // inspect
            subject("urn:mms:inspect") { ignoreAll() }
        }
    }
}
