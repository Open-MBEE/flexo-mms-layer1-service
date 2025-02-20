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
    val demoBranchId = "new-branch"
    val demoBranchName = "New Branch"
    val demoBranchPath = "$demoRepoPath/branches/$demoBranchId"
    val masterBranchPath = "$demoRepoPath/branches/master"

    val basePathLocks = "$demoRepoPath/locks"

    val demoLockId = "new-lock"
    val demoLockPath = "$basePathLocks/$demoLockId"

    val validLockBodyfromMaster = withAllTestPrefixes("""
        <> mms:ref <../branches/master> .
    """.trimIndent())

    val validBranchBodyFromMaster = title(demoBranchName)+validLockBodyfromMaster

    var repoEtag = ""

    // create a repo before each branch test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        repoEtag = createRepo(demoOrgPath, demoRepoId, demoRepoName).response.headers[HttpHeaders.ETag]!!
    }

    fun TestApplicationCall.validateCreateBranchResponse(fromCommit: String) {
        // branch-specific validation
        response exclusivelyHasTriples {
            modelName = "CreateBranch"

            subject(localIri(demoBranchPath)) {
                includes(
                    RDF.type exactly MMS.Branch,
                    MMS.id exactly demoBranchId,
                    DCTerms.title exactly demoBranchName.en,
                    MMS.etag exactly response.headers[HttpHeaders.ETag]!!,
                    MMS.commit startsWith localIri("$demoCommitsPath/").iri,  // TODO: incorporate fromCommit ?
                    MMS.createdBy exactly userIri("root").iri
                )
            }

            // auto policy
            matchOneSubjectTerseByPrefix("m-policy:AutoBranchOwner") {
                includes(
                    RDF.type exactly MMS.Policy,
                )
            }

            // transaction
            validateTransaction(demoOrgPath, demoRepoPath, demoBranchPath, "root")

            // inspect
            subject(MMS_URNS.SUBJECT.inspect) { ignoreAll() }
        }
    }
}
