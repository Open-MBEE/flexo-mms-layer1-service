package org.openmbee.flexo.mms

import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*

// for ldp endpoints in scratches.kt
class ScratchLdpDc: ScratchAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = basePathScratches,
            resourceId = demoScratchId,
            validBodyForCreate = withAllTestPrefixes("""
                $validScratchBody
                <> <$arbitraryPropertyIri> "$arbitraryPropertyValue" .
            """.trimIndent()),
            resourceCreator = { createScratch(demoScratchPath, demoScratchName) }
        ) {
            // FIXME this function needs rewriting - done?
            fun TriplesAsserter.validCreatedScratch(response: TestApplicationResponse, slug: String) {
                modelName = "CreateValidScratch"

                validateCreatedScratchTriples(response, slug, demoRepoId, demoOrgId, demoScratchName, listOf(
                    arbitraryPropertyIri.toPredicate exactly arbitraryPropertyValue,
                ))
            }

            create {response, slug ->
                validCreatedScratch(response, slug)
            }

            postWithPrecondition { testName ->
                validateCreatedLdpResource(testName) { response, slug ->
                    validCreatedScratch(response, slug)
                }
            }

            replaceExisting {
                it includesTriples {
                    // will error if etag have multiple values or created/createdBy doesn't exist
                    validateScratchTriples(demoScratchId, demoRepoId, demoOrgId, demoScratchName, listOf(
                        arbitraryPropertyIri.toPredicate exactly arbitraryPropertyValue,
                    ))
                }
            }

            read(
                { createScratch(fooScratchPath, fooScratchName) },
                { createScratch(barScratchPath, barScratchName) },
            ) {
                if(it.createdOthers.isEmpty()) {
                    it.response exclusivelyHasTriples {
                        validateScratchTriples(demoScratchId, demoRepoId, demoOrgId, demoScratchName)
                    }
                }
                else {
                    it.response includesTriples {
                        validateScratchTriples(demoScratchId, demoRepoId, demoOrgId, demoScratchName)
                        validateScratchTriples(fooScratchId, demoRepoId, demoOrgId, fooScratchName)
                        validateScratchTriples(barScratchId, demoRepoId, demoOrgId, barScratchName)
                    }
                }
            }

            patch()
        }
    }
}
