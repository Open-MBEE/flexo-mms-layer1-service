package org.openmbee.flexo.mms.routes.ldp

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.assertLegalId
import org.openmbee.flexo.mms.plugins.linkedDataPlatformDirectContainer

const val SPARQL_VAR_NAME_ORG = "_org"


/**
 * Org CRUD routing
 */
fun Route.CrudOrgs() {
    // all orgs
    linkedDataPlatformDirectContainer("/orgs") {
        // state of all orgs
        head {
            headOrgs()
        }

        // read all orgs
        get {
            getOrgs()
        }

        // create a new org
        post { slug ->
            // assert id is legal
            assertLegalId(slug)

            // set org id on context
            orgId = slug

            // create new org
            createOrReplaceOrg()
        }
    }

    // specific org
    linkedDataPlatformDirectContainer("/orgs/{orgId}") {
        beforeEach = {
            parsePathParams {
                org()
            }
        }

        // state of an org
        head {
            headOrgs(orgId)
        }

        // read an org
        get {
            getOrgs(orgId)
        }

        // create or replace org
        put {
            // assert id is legal when new resource is being created
            assertLegalId(orgId!!)

            // create/replace org
            createOrReplaceOrg()
        }

        // modify existing org
        patch {
//            guardedPatch(
//                updateRequest = it,
//                objectKey = "mo",
//                graph = "m-graph:Cluster",
//                preconditions = UPDATE_ORG_CONDITIONS,
//            )
        }
    }
}