package org.openmbee.flexo.mms

import io.kotest.matchers.shouldBe
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*


class LockWrite : LockAny() {
    init {
        "patch lock" {
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPatch(demoLockPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        <> rdfs:comment "foo" .
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }
    }
}
