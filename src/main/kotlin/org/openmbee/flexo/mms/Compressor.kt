package org.openmbee.flexo.mms

import org.apache.jena.rdf.model.Model

fun c1Subject() {

}

private val COMPRESS_IRI = """(.*?)([^/#]*)""".toRegex()

class Compressor {
    var prefixes: HashMap<String, Int> = HashMap()
    var prefixCount = 0

    fun load(model: Model) {
        for(subject in model.listSubjects()) {
            var subjectC1: String

            if(subject.isResource) {
                val subjectIri = subject.uri
                val (prefix, suffix) = COMPRESS_IRI.matchEntire(subjectIri)!!.destructured

                var prefixId: Int? = prefixes[prefix]
                if(prefixId == null) {
                    prefixId = prefixCount++
                    prefixes[prefix] = prefixId
                }

                subjectC1 = ">"+prefixId.toChar().toString()+suffix
            }
            else if(subject.isAnon) {
                subjectC1 = "#"+subject.id
            }

         //   prefixes[]
        }
    }
}
