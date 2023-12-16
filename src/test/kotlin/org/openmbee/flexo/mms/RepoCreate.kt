package org.openmbee.flexo.mms


import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*


class RepoCreate : RepoAny() {
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
        }
    }
}