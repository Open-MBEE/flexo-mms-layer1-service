package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.ldp.createOrReplaceOrg
import org.openmbee.flexo.mms.routes.ldp.getOrgs
import org.openmbee.flexo.mms.routes.ldp.headOrgs
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

const val SPARQL_VAR_NAME_ORG = "_org"

private const val ORGS_PATH = "/orgs"

/**
 * Org CRUD routing
 */
fun Route.crudOrgs() {
    // all orgs
    linkedDataPlatformDirectContainer(ORGS_PATH) {
        // state of all orgs
        head {
            headOrgs(true)
        }

        // read all orgs
        get {
            getOrgs(true)
        }

        // create a new org
        post { slug ->
            // set org id on context
            orgId = slug

            // create new org
            createOrReplaceOrg()
        }
    }

    // specific org
    linkedDataPlatformDirectContainer("$ORGS_PATH/{orgId}") {
        beforeEach = {
            parsePathParams {
                org()
            }
        }

        // state of an org
        head {
            headOrgs()
        }

        // read an org
        get {
            getOrgs()
        }

        // create or replace org
        put {
            createOrReplaceOrg()
        }

        // modify existing org
        patch {
            // build conditions
            val localConditions = GLOBAL_CRUD_CONDITIONS.append {
                // org must exist
                orgExists()

                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph m-graph:Cluster {
                            mo: mms:etag ?__mms_etag .
                            ${values.reindent(6)}
                        }
                    """
                }

                // require that the user has the ability to update this org on an org-level scope
                permit(Permission.UPDATE_ORG, Scope.ORG)
            }

            // handle all varieties of accepted PATCH request formats
            guardedPatch(
                updateRequest = it,
                objectKey = "mo",
                graph = "m-graph:Cluster",
                preconditions = localConditions,
            )
        }
    }
}