package org.openmbee.flexo.mms


import io.ktor.client.statement.*
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
            fun TriplesAsserter.validCreatedRepo(response: HttpResponse, slug: String) {
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

            replaceExisting {
                it includesTriples {
                    // will error if etag have multiple values or created/createdBy doesn't exist
                    validateRepoTriples(demoRepoId, demoRepoName, demoOrgPath, listOf(
                        arbitraryPropertyIri.toPredicate exactly arbitraryPropertyValue,
                    ))
                }
            }

            read(
                { createRepo(demoOrgPath, fooRepoId, fooRepoName) },
                { createRepo(demoOrgPath, barRepoId, barRepoName) },
            ) {
                if(it.createdOthers.isEmpty()) {
                    it.response includesTriples {
                        validateRepoTriples(demoRepoId, demoRepoName, demoOrgPath)
                    }
                }
                else {
                    it.response includesTriples {
                        validateRepoTriples(demoRepoId, demoRepoName, demoOrgPath)
                        validateRepoTriples(fooRepoId, fooRepoName, demoOrgPath)
                        validateRepoTriples(barRepoId, barRepoName, demoOrgPath)
                    }
                }
            }

            patch()

//            delete()
        }
    }
}