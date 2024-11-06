package org.openmbee.flexo.mms.routes.ldp

import org.openmbee.flexo.mms.Layer1Context
import org.openmbee.flexo.mms.NotImplementedException
import org.openmbee.flexo.mms.server.GenericRequest
import org.openmbee.flexo.mms.server.LdpPatchResponse


suspend fun<TRequestContext: GenericRequest> Layer1Context<TRequestContext, LdpPatchResponse>.patchArtifactsMetadata() {
    throw NotImplementedException("patch metadata artifact")
}
