package org.openmbee.flexo.mms

import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.DCTerms

class RdfModeler(val layer1: Layer1Context<*, *>, val baseIri: String, val content: String) {
    val model = KModel(layer1.prefixes) {
        parseTurtle(content, this, baseIri)
    }

    private fun resourceFromParamPrefix(prefixId: String, suffix: String?=null): Resource {
        val uri = layer1.prefixes[prefixId]?: throw ParamNotParsedException(prefixId)

        return model.createResource(uri+(suffix?: ""))
    }

    fun userNode(): Resource {
        return resourceFromParamPrefix("mu")
    }

    fun groupNode(): Resource {
        return resourceFromParamPrefix("mg")
    }

    fun orgNode(): Resource {
        return resourceFromParamPrefix("mo")
    }

    fun collectionNode(): Resource {
        return resourceFromParamPrefix("moc")
    }

    fun repoNode(): Resource {
        return resourceFromParamPrefix("mor")
    }

    fun branchNode(): Resource {
        return resourceFromParamPrefix("morb")
    }

    fun commitNode(): Resource {
        return resourceFromParamPrefix("morc")
    }

    fun lockNode(): Resource {
        return resourceFromParamPrefix("morl")
    }

    fun diffNode(): Resource {
        return resourceFromParamPrefix("mord")
    }

    fun policyNode(): Resource {
        return resourceFromParamPrefix("mp")
    }

    fun transactionNode(subTxnId: String?=null): Resource {
        return resourceFromParamPrefix("mt", subTxnId)
    }

    fun normalizeRefOrCommit(node: Resource) {
        val refs = node.listProperties(MMS.ref).toList()
        val commits = node.listProperties(MMS.commit).toList()
        val sourceCount = refs.size + commits.size
        if(1 == sourceCount) {
            if(1 == refs.size) {
                layer1.refSource = if(!refs[0].`object`.isURIResource) {
                    throw ConstraintViolationException("object of `mms:ref` predicate must be an IRI")
                } else {
                    refs[0].`object`.asResource().uri
                }

                refs[0].remove()
            }
            else {
                layer1.commitSource = if(!commits[0].`object`.isURIResource) {
                    throw ConstraintViolationException("object of `mms:commit` predicate must be an IRI")
                } else {
                    commits[0].`object`.asResource().uri
                }

                commits[0].remove()
            }
        }
        else if(0 == sourceCount) {
            throw ConstraintViolationException("must specify a ref or commit source using `mms:ref` or `mms:commit` predicate, respectively")
        }
        else if(sourceCount > 1) {
            throw ConstraintViolationException("must specify exactly one ref or commit; but too many sources were specified")
        }
    }

    fun Resource.extract1OrMoreUris(predicate: Property): List<Resource> {
        val statements = listProperties(predicate).toList()
        if(statements.isEmpty()) {
            throw ConstraintViolationException("missing triples having required property `${layer1.prefixes.terse(predicate)}`")
        }

        return statements.map {
            if(!it.`object`.isURIResource) {
                throw ConstraintViolationException("object of `${layer1.prefixes.terse(predicate)}` predicate must be an IRI")
            }

            it.`object`.asResource()
        }
    }

    fun Resource.extractExactly1Uri(predicate: Property): Resource {
        val statements = listProperties(predicate).toList()
        if(statements.isEmpty()) {
            throw ConstraintViolationException("missing triples having required property `${layer1.prefixes.terse(predicate)}`")
        }
        else if(statements.size > 1) {
            throw ConstraintViolationException("must specify exactly one `${layer1.prefixes.terse(predicate)}` but ${statements.size} were specified")
        }
        else if(!statements[0].`object`.isURIResource) {
            throw ConstraintViolationException("object of `${layer1.prefixes.terse(predicate)}` predicate must be an IRI")
        }

        return statements[0].`object`.asResource()
    }

    fun Resource.sanitizeCrudObject(setup: (Sanitizer.()->Unit)?=null) {
        removeNonLiterals(DCTerms.title)

        if(setup != null) {
            Sanitizer(layer1, this@sanitizeCrudObject).apply {
                setup(this)
                finalize()
            }
        }
    }
}
