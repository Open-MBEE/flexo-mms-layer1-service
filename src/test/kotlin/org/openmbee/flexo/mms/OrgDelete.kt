package org.openmbee.flexo.mms



import io.ktor.http.*
import org.openmbee.flexo.mms.util.*
import java.util.*

class OrgDelete : OrgAny() {
    init {
        "delete org".config(enabled=false) {
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