package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.openmbee.mms5.util.httpHead
import org.openmbee.mms5.util.httpPut
import org.openmbee.mms5.util.setTurtleBody
import org.openmbee.mms5.util.withTest

class LockCreate : LockAny() {
    "head non-existent lock" {
        withTest {
            httpHead(lockPath) {}.apply {
                response shouldHaveStatus HttpStatusCode.NotFound
            }
        }
    }

    "put new lock" {
        withTest {
            httpPut(lockPath) {
                setTurtleBody("""
                    <> mms:ref <./master> .
                """.trimIndent())
            }.apply {

            }
        }
    }
}