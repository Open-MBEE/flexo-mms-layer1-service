package org.openmbee

import org.apache.jena.datatypes.BaseDatatype
import org.apache.jena.rdf.model.impl.PropertyImpl
import org.apache.jena.rdf.model.impl.ResourceImpl

object MMS {
    private val _BASE = SPARQL_PREFIXES["mms"]

    // classes
    val Org = ResourceImpl("${_BASE}Org")
    val Project = ResourceImpl("${_BASE}Project")

    // properties
    val id  = PropertyImpl("${_BASE}id")
    val org = PropertyImpl("${_BASE}org")
    val orgId = PropertyImpl("${_BASE}serviceId")
    val project = PropertyImpl("${_BASE}project")
    val projectId = PropertyImpl("${_BASE}serviceId")
    val commitId = PropertyImpl("${_BASE}commitId")
    val created = PropertyImpl("${_BASE}created")
    val serviceId = PropertyImpl("${_BASE}serviceId")
}

object MMS_DATATYPE {
    private val _BASE = SPARQL_PREFIXES["mms-datatype"]

    val commitMessage = BaseDatatype("${_BASE}commitMessage")
}
