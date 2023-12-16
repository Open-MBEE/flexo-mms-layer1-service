package org.openmbee.flexo.mms



import io.ktor.http.*
import org.openmbee.flexo.mms.util.*

class OrgDelete : OrgAny() {
    init {
        "delete org".config(enabled=false) {
            createOrg(demoOrgId, demoOrgName)

            withTest {
                // delete org should work
                httpDelete(demoOrgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }

                // get deleted org should 404
                httpGet(demoOrgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }
    }
}