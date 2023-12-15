package org.openmbee.flexo.mms


import org.openmbee.flexo.mms.util.LinkedDataPlatformDirectContainerTests
import org.openmbee.flexo.mms.util.exactly
import org.openmbee.flexo.mms.util.toPredicate
import org.openmbee.flexo.mms.util.withAllTestPrefixes

class RepoCreate : RepoAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = "/orgs/$orgId/repos",
            resourceId = repoId,
            validBodyForCreate = withAllTestPrefixes("""
                $validRepoBody
                <> <$arbitraryPropertyIri> "$arbitraryPropertyValue" .
            """.trimIndent())
        ) {
            create {
                modelName = "CreateValidRepo"

                validateCreatedRepoTriples(it, repoId, repoName, orgPath, listOf(
                    arbitraryPropertyIri.toPredicate exactly arbitraryPropertyValue,
                ))
            }
        }
    }
}