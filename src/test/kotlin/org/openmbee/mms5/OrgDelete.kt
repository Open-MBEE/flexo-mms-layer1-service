package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.openmbee.mms5.util.*
import java.util.*

class OrgDelete : OrgAny() {
    init {
        "delete org" {
            createOrg(orgId, orgName)

            withTest {
                // delete org should work
                httpDelete(orgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }

                // get deleted org should 404
                httpGet(orgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }
    }
}