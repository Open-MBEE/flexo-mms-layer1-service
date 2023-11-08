package org.openmbee.flexo.mms.util


class RemoteBackend : SparqlBackend {
    override fun start() {}

    override fun stop() {}

    override fun getQueryUrl(): String {
        return System.getenv("FLEXO_MMS_QUERY_URL")
    }

    override fun getUpdateUrl(): String {
        return System.getenv("FLEXO_MMS_UPDATE_URL")
    }

    override fun getMasterQueryUrl(): String {
        return System.getenv("FLEXO_MMS_MASTER_QUERY_URL")
    }

    override fun getGspUrl(): String {
        return System.getenv("FLEXO_MMS_GRAPH_STORE_PROTOCOL_URL")
    }

}