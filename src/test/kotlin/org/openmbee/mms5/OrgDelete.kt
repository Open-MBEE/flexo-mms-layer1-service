package org.openmbee.mms5



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