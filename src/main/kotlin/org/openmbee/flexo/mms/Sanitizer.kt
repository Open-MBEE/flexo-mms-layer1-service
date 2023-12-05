package org.openmbee.flexo.mms

import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.ResourceFactory

class Sanitizer(val layer1: Layer1Context<*, *>, val node: Resource) {
    private val explicitUris = hashMapOf<String, Resource>()
    private val explicitUriSets = hashMapOf<String, List<Resource>>()
    private val explicitLiterals = hashMapOf<String, String>()
    private val bypassProperties = hashSetOf<String>()

    fun setProperty(property: Property, value: Resource, unsettable: Boolean?=false) {
        // user document includes triple(s) about this property; ensure value is acceptable
        node.listProperties(property).forEach { input ->
            // not acceptable
            if(input.`object` != value) throw ConstraintViolationException("user not allowed to set `${layer1.prefixes.terse(property)}` property${if(unsettable == true) "" else " to anything other than <${node.uri}>"}")

            // verbose
            layer1.log("Removing statement from user input: ${input.asTriple()}")

            // remove from model
            input.remove()
        }

        // set value
        explicitUris[property.uri] = value
    }

    fun setProperty(property: Property, value: String, unsettable: Boolean?=false) {
        // user document includes triple(s) about this property; ensure value is acceptable
        node.listProperties(property).forEach { input ->
            // not acceptable
            if(!input.`object`.isLiteral || input.`object`.asLiteral().string != value) throw ConstraintViolationException("user not allowed to set `${layer1.prefixes.terse(property)}` property${if(unsettable == true) "" else " to anything other than \"${value}\"`"}")

            // verbose
            layer1.log("Removing statement from user input: ${input.asTriple()}")

            // remove from model
            input.remove()
        }

        // set value
        explicitLiterals[property.uri] = value
    }

    fun setProperty(property: Property, value: List<Resource>, unsettable: Boolean?=false) {
        // user document includes triple(s) about this property; ensure value is acceptable
        node.listProperties(property).forEach { input ->
            // not acceptable
            if(value.contains(input.`object`)) {
                throw ConstraintViolationException("user not allowed to set `${layer1.prefixes.terse(property)}` property${
                    if(unsettable == true) ""
                    else " to anything not included in [${value.joinToString(",") { "<${it.uri}>" }}]"
                }")
            }

            // verbose
            layer1.log("Removing statement from user input: ${input.asTriple()}")

            // remove from model
            input.remove()
        }

        // set value
        explicitUriSets[property.uri] = value
    }

    fun bypass(property: Property) {
        bypassProperties.add(property.uri)
    }

    fun finalize() {
        // check each property
        node.listProperties().forEach {
            val predicateUri = it.predicate.uri

            // bypass property
            if(bypassProperties.contains(predicateUri)) {
                return@forEach;
            }
            // sensitive property
            else if(predicateUri.contains(FORBIDDEN_PREDICATES_REGEX)) {
                throw ConstraintViolationException("user not allowed to set <$predicateUri> property because it belongs to a restricted namespace")
            }
        }

        // add back explicit properties
        for(entry in explicitUris) {
            val property = ResourceFactory.createProperty(entry.key)
            node.addProperty(property, entry.value)
        }

        // add back explicit literals
        for(entry in explicitLiterals) {
            val property = ResourceFactory.createProperty(entry.key)
            node.addProperty(property, entry.value)
        }
    }
}
