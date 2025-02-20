package org.openmbee.flexo.mms

import io.ktor.http.*
import org.openmbee.flexo.mms.util.LinkedDataPlatformDirectContainerTests
import org.openmbee.flexo.mms.util.createLock


class LockLdpDc : LockAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = basePathLocks,
            resourceId = demoLockId,
            validBodyForCreate = validLockBodyfromMaster,
            resourceCreator = { createLock(demoRepoPath, masterBranchPath, demoLockId) }
        ) {

//            create {
//                // extract etag
//                val etag = it.headers[HttpHeaders.ETag]!!
//
//                // validate
//                validateCreatedLockTriples(demoLockId, etag, demoOrgPath)
//            }

            patch()
        }
    }
}
