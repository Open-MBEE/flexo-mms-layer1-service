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

    fun saveToFile()

    /**
     * Get the URL to run SPARQL queries on
     */
    fun getQueryUrl(): String

    /**
     * Get the URL to run SPARQL updates on
     */
    fun getUpdateUrl(): String

    /**
     * Get the URL to directly upload RDF data to
     */
    fun getUploadUrl(): String
}