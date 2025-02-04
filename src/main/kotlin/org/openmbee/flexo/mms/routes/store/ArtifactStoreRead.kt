package org.openmbee.flexo.mms.routes.store

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.GenericRequest
import org.openmbee.flexo.mms.server.StorageAbstractionReadResponse
import java.time.Instant
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val SPARQL_BIND_ARTIFACT = """
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

suspend fun <TRequestContext: GenericRequest> Layer1Context<TRequestContext, StorageAbstractionReadResponse>.decodeArtifact(artifact: Resource): DecodedArtifact {
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
    return when(datatype.uri) {
        // base64 binary
        XSD.base64Binary.uri -> {
            DecodedArtifact(contentType, bodyBinary = Base64.getDecoder().decode(bodyLiteral.string))
        }
        // plain UTF-8 string
        XSD.xstring.uri -> {
            DecodedArtifact(contentType, bodyText = bodyLiteral.string)
        }
        XSD.anyURI.uri -> {
            var storeServiceUrl: String? = call.application.storeServiceUrl
            val path = bodyLiteral.string
            val response: HttpResponse = defaultHttpClient.get("$storeServiceUrl/$path") {
                // Pass received authorization to internal service, this shouldn't be needed..
                headers {
                    call.request.headers[HttpHeaders.Authorization]?.let { auth: String ->
                        append(HttpHeaders.Authorization, auth)
                    }
                }
            }
            val bytes = response.readBytes()
            DecodedArtifact(contentType, bodyBinary = bytes)
        }
        else -> {
            throw ServerBugException("Artifact body has unrecognized datatype: ${datatype.uri}")
        }
    }
}

suspend fun<TRequestContext: GenericRequest> Layer1Context<TRequestContext, StorageAbstractionReadResponse>.getArtifactsStore(allArtifacts: Boolean?=false) {
    val authorizedIri = "<${MMS_URNS.SUBJECT.auth}:${transactionId}>"

    // build the construct query
    val constructString = buildSparqlQuery {
        construct {
            // output auth info and artifact bindings
            raw("""
                $authorizedIri <${MMS_URNS.PREDICATE.policy}> ?__mms_authMethod .
                
                $SPARQL_BIND_ARTIFACT
            """)
        }
        where {
            // set authentication parameters
            auth(Permission.READ_ARTIFACT.scope.id, ARTIFACT_QUERY_CONDITIONS)

            // provide artifact bind pattern
            raw("""
                optional{
                    graph mor-graph:Artifacts {
                        $SPARQL_BIND_ARTIFACT
                    }
                }
            """)
            raw(permittedActionSparqlBgp(Permission.READ_ARTIFACT, Scope.REPO))
        }
    }

    // finalize construct query and execute
    val constructResponseText = executeSparqlConstructOrDescribe(constructString) {
        acceptReplicaLag = true

        prefixes(prefixes)

        // single artifact; replace the ?artifact variable with the target IRI
        if(allArtifacts == false) {
            iri(
                "artifact" to prefixes["mora"]!!
            )
        }
    }

    // missing authorized IRI, auth failed
    if(!constructResponseText.contains(authorizedIri)) {
        log("Rejecting unauthorized request with 404\n${constructResponseText}")

        if(call.application.glomarResponse) {
            throw Http404Exception(call.request.path())
        }
        else {
            throw Http403Exception(this, call.request.path())
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
                    if (artifactResource.uri.contains(MMS_URNS.SUBJECT.auth)){
                        continue
                    }
                    // decode artifact
                    val decoded = decodeArtifact(artifactResource)

                    // create zip entry - can't use artifactResource.localName, it drops number characters from beginning of URI
                    val entry = ZipEntry(artifactResource.toString().split("/").last()+"."+decoded.extension)

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
