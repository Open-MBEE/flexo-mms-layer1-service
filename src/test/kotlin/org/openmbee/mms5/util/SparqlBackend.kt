package org.openmbee.mms5.util

/**
 * Interface for SPARQL backend store implementations
 */
interface SparqlBackend {

    /**
     * Start the service
     */
    fun start()

    /**
     * Stop the service
     */
    fun stop()

    /**
     * Get the URL to run SPARQL queries on during read operations
     */
    fun getQueryUrl(): String

    /**
     * Get the URL to run SPARQL updates on
     */
    fun getUpdateUrl(): String

    /**
     * Get the URL to the graph store protocol endpoint
     */
    fun getGspUrl(): String

    /**
     * Get the URL to run SPARQL queries on during write operations
     */
    fun getMasterQueryUrl(): String {
        return this.getQueryUrl()
    }
}