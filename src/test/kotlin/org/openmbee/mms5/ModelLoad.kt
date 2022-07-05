package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.openmbee.mms5.util.httpPost
import org.openmbee.mms5.util.setTurtleBody
import org.openmbee.mms5.util.withTest

class ModelLoad : ModelAny() {
    init {
        "load turtle file" {
            withTest {
                httpPost("$masterPath/graph") {
                    setTurtleBody(loadTurtle)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }
    }
}
