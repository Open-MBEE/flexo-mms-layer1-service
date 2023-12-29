package org.openmbee.flexo.mms


import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*


class RepoLdpDc : RepoAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = basePathRepos,
            resourceId = demoRepoId,
            validBodyForCreate = withAllTestPrefixes("""
                $validRepoBody
                <> <$arbitraryPropertyIri> "$arbitraryPropertyValue" .
            """.trimIndent())
        ) {
            fun TriplesAsserter.validCreatedRepo(response: TestApplicationResponse) {
                modelName = "CreateValidRepo"

                validateCreatedRepoTriples(response, demoRepoId, demoRepoName, demoOrgPath, listOf(
                    arbitraryPropertyIri.toPredicate exactly arbitraryPropertyValue,
                ))
            }

            create {
                validCreatedRepo(it)
            }

            postWithPrecondition { testName ->
                validateCreatedLdpResource(testName) {
                    validCreatedRepo(it)
                }
            }

            read(
                { createRepo(demoOrgPath, demoRepoId, demoRepoName) },
                { createRepo(demoOrgPath, fooRepoId, fooRepoName) },
                { createRepo(demoOrgPath, barRepoId, barRepoName) },
            ) {
                if(it.createdOthers.isEmpty()) {
                    it.response exclusivelyHasTriples {
                        validateRepoTriplesWithMasterBranch(demoRepoId, demoRepoName, demoOrgPath)
                    }
                }
                else {
                    it.response includesTriples {
                        validateRepoTriplesWithMasterBranch(demoRepoId, demoRepoName, demoOrgPath)
                        validateRepoTriplesWithMasterBranch(fooRepoId, fooRepoName, fooOrgPath)
                        validateRepoTriplesWithMasterBranch(barRepoId, barRepoName, barOrgPath)
                    }
                }
            }
        }
    }
}