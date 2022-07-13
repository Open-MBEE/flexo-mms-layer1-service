package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
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
    val lockName = "New Lock"
    val lockPath = "$repoPath/locks/$lockId"

    val validBranchBodyFromMaster = """
        <> dct:title "$branchName"@en .
        <> mms:ref <../branches/master> .
    """.trimIndent()

    val fromMaster = "<> mms:ref <../branches/master> .\n"

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
            modelName = "response"

            subject(localIri(branchPath)) {
                includes(
                    RDF.type exactly MMS.Branch,
                    MMS.id exactly branchId,
                    DCTerms.title exactly branchName.en,
                    MMS.etag exactly response.headers[HttpHeaders.ETag]!!,
                    MMS.commit exactly localIri("$commitsPath/$fromCommit").iri,
                    MMS.createdBy exactly userIri("root").iri
                )
            }
            /*
            //currently it returns AutoOrgOwner, shouldn't it be branch owner? or repo owner,
            //or does it check for the same user as orgonwer??
            matchOneSubjectTerseByPrefix("m-policy:AutoOrgOwner") {
                includes(
                    RDF.type exactly MMS.Policy,
                )
            }*/
            subjectTerse("mt:") {
                includes(
                    RDF.type exactly MMS.Transaction,
                    MMS.created hasDatatype XSD.dateTime,
                    MMS.org exactly orgPath.iri,
                    MMS.repo exactly repoPath.iri,
                    MMS.branch exactly branchPath.iri,
                    MMS.user exactly userIri("root").iri
                )
            }

            // inspect
            subject("urn:mms:inspect") { ignoreAll() }
        }
    }
}
