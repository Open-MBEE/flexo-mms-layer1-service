package org.openmbee.flexo.mms

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.ktor.http.*

class UpdateRetryTests : StringSpec({
    "a deadlock that the store rolled back is retried" {
        isRolledBackDeadlock(Non200Response("Virtuoso 40001 Error SR172: Transaction deadlocked", HttpStatusCode.InternalServerError)).shouldBeTrue()
        isRolledBackDeadlock(Non200Response("SQLSTATE 40001 serialization failure", HttpStatusCode.ServiceUnavailable)).shouldBeTrue()
    }

    "other store errors are not retried" {
        isRolledBackDeadlock(Non200Response("Virtuoso 37000 Error SP030: SPARQL compiler, line 43: syntax error", HttpStatusCode.BadRequest)).shouldBeFalse()
        isRolledBackDeadlock(Non200Response("Virtuoso 22023 Error SR578: string too large", HttpStatusCode.InternalServerError)).shouldBeFalse()
        // client errors are never retried, even if the text mentions a deadlock
        isRolledBackDeadlock(Non200Response("Transaction deadlocked", HttpStatusCode.BadRequest)).shouldBeFalse()
    }
})
