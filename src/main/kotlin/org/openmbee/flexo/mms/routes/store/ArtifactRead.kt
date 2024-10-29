package org.openmbee.flexo.mms.routes.store

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.Layer1Context
import org.openmbee.flexo.mms.MMS
import org.openmbee.flexo.mms.ServerBugException
import org.openmbee.flexo.mms.parseConstructResponse
import org.openmbee.flexo.mms.server.GenericRequest
import org.openmbee.flexo.mms.server.StorageAbstractionReadResponse
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val SPARQL_BIND_ARTIFACT = """
    ?artifact a mms:Artifact ;
        mms:contentType ?contentType ;
        mms:body ?body ;
        ?artifact_p ?artifact_o ;
        .    
"""

data class DecodedArtifact(
    val contentType: ContentType,
    val bodyText: String?=null,
    val bodyBinary: ByteArray?=null
) {
    val extension: String
        get() = try {
            contentType.fileExtensions().first()
        } catch(e: NoSuchElementException) {
            "dat"
        }

    val bodyBytes: ByteArray
        get() = bodyBinary ?: bodyText!!.toByteArray()
}

suspend fun decodeArtifact(artifact: Resource): DecodedArtifact {
    // read its content type
    val contentTypeString = artifact.getProperty(MMS.contentType).`object`.asLiteral().string

    // parse
    val contentType = ContentType.parse(contentTypeString)

    // ready its body
    val bodyProperty = artifact.getProperty(MMS.body).`object`

    // not a literal
    if(!bodyProperty.isLiteral) {
        throw ServerBugException("Artifact body must be a literal")
    }

    // as literal
    val bodyLiteral = bodyProperty.asLiteral()

    // route datatype
    val datatype = bodyLiteral.datatype
    return when(datatype) {
        // base64 binary
        XSD.base64Binary -> {
            DecodedArtifact(contentType, bodyBinary = Base64.getDecoder().decode(bodyLiteral.string))
        }

        // URI
        XSD.anyURI -> {
            // TODO: fetch from S3
            DecodedArtifact(contentType, bodyBinary = ByteArray(0))
        }

        // plain UTF-8 string
        XSD.xstring -> {
            DecodedArtifact(contentType, bodyText = bodyLiteral.string)
        }

        else -> {
            throw ServerBugException("Artifact body has unrecognized datatype: ${datatype.uri}")
        }
    }
}

suspend fun<TRequestContext: GenericRequest> Layer1Context<TRequestContext, StorageAbstractionReadResponse>.getArtifacts(allArtifacts: Boolean?=false) {
    val constructString = buildSparqlQuery {
        construct {
            raw(SPARQL_BIND_ARTIFACT)
        }
        where {
            graph("mor-graph:Artifacts") {
                raw(SPARQL_BIND_ARTIFACT)
            }
        }
    }

    val constructResponseText = executeSparqlConstructOrDescribe(constructString) {
        prefixes(prefixes)

        // single artifact
        if(allArtifacts == false) {
            iri(
                "artifact" to prefixes["mora"]!!
            )
        }
    }

    // parse construct response
    val model = parseConstructResponse(constructResponseText) {}

    // all artifacts
    if(allArtifacts == true) {
        // timestamp for download name
        val time = Instant.now().toString().replace(":", "-")

        // set content disposition
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$orgId - $repoId.artifacts.$time.zip\"")

        // close the response with a ZIP file
        return call.respondOutputStream(contentType = ContentType.Application.Zip) {
            ZipOutputStream(this).use { stream ->
                // each artifact
                for (artifactResource in model.listSubjects()) {
                    // decode artifact
                    val decoded = decodeArtifact(artifactResource)

                    // create zip entry
                    val entry = ZipEntry(artifactResource.localName+"."+decoded.extension)

                    // open the entry
                    stream.putNextEntry(entry)

                    // copy contents to entry
                    stream.write(decoded.bodyBytes)

                    // close the entry
                    stream.closeEntry()
                }
            }
        }
    }
    // single artifact
    else {
        val artifactResource = model.getResource(prefixes["mora"])

        // decode artifact
        val decoded = decodeArtifact(artifactResource)

        // respond
        call.respondBytes(decoded.bodyBytes, decoded.contentType, HttpStatusCode.OK)
    }
}
