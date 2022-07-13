package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.openmbee.mms5.util.httpRequest
import org.openmbee.mms5.util.withTest

class LockRead : LockAny() {
    init {
        listOf(
            "head",
            "get",
        ).forEach { method ->
            "$method non-existent lock" {
                withTest {
                    httpRequest(HttpMethod(method.uppercase()), lockPath) {}.apply {
                        response shouldHaveStatus HttpStatusCode.NotFound
                    }
                }
            }
        }
    }
}
