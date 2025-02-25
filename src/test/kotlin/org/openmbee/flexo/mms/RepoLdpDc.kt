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
            """.trimIndent()),
            resourceCreator = { createRepo(demoOrgPath, demoRepoId, demoRepoName) }
        ) {
            fun TriplesAsserter.validCreatedRepo(response: TestApplicationResponse, slug: String) {
                modelName = "CreateValidRepo"

                validateCreatedRepoTriples(response, slug, demoRepoName, demoOrgPath, listOf(
                    arbitraryPropertyIri.toPredicate exactly arbitraryPropertyValue,
                ))
            }

            create {response, slug ->
                validCreatedRepo(response, slug)
            }

            postWithPrecondition { testName ->
                validateCreatedLdpResource(testName) { response, slug ->
                    validCreatedRepo(response, slug)
                }
            }

            read(
                { createRepo(demoOrgPath, fooRepoId, fooRepoName) },
                { createRepo(demoOrgPath, barRepoId, barRepoName) },
            ) {
                if(it.createdOthers.isEmpty()) {
                    it.response includesTriples {
                        validateRepoTriplesWithMasterBranch(demoRepoId, demoRepoName, demoOrgPath)
                    }
                }
                else {
                    it.response includesTriples {
                        validateRepoTriplesWithMasterBranch(demoRepoId, demoRepoName, demoOrgPath)
                        validateRepoTriplesWithMasterBranch(fooRepoId, fooRepoName, demoOrgPath)
                        validateRepoTriplesWithMasterBranch(barRepoId, barRepoName, demoOrgPath)
                    }
                }
            }

            patch()

//            delete()
        }
    }
}