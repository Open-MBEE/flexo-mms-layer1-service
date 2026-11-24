package org.openmbee.flexo.mms.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

private const val ISSUER = "https://jwt-provider-domain/"
private const val AUDIENCE = "jwt-audience"
private const val MCP_RESOURCE = "https://mcp.example/mcp"
private const val SECRET = "test1234"
private const val KID = "test-key"

class AuthenticationTest : StringSpec({
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val publicKey = keyPair.public as RSAPublicKey
    val privateKey = keyPair.private as RSAPrivateKey

    val jwks = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/jwks.json") { exchange ->
            val b64 = Base64.getUrlEncoder().withoutPadding()
            fun unsigned(bytes: ByteArray) = if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
            val body = """{"keys":[{"kty":"RSA","kid":"$KID","use":"sig","alg":"RS256",""" +
                """"n":"${b64.encodeToString(unsigned(publicKey.modulus.toByteArray()))}",""" +
                """"e":"${b64.encodeToString(unsigned(publicKey.publicExponent.toByteArray()))}"}]}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        start()
    }
    afterSpec { jwks.stop(0) }

    fun token(audiences: List<String>, algorithm: Algorithm, kid: String? = null) = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(*audiences.toTypedArray())
        .apply { kid?.let { withKeyId(it) } }
        .withClaim("username", "alice")
        .withClaim("groups", listOf("engineers"))
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(algorithm)

    suspend fun whoami(bearer: String): Pair<HttpStatusCode, String> {
        var result: Pair<HttpStatusCode, String>? = null
        testApplication {
            environment {
                config = MapApplicationConfig(
                    "jwt.domain" to ISSUER,
                    "jwt.audience" to AUDIENCE,
                    "jwt.realm" to "test",
                    "jwt.secret" to SECRET,
                    "jwt.jwksUrl" to "http://127.0.0.1:${jwks.address.port}/jwks.json",
                )
            }
            application {
                configureAuthentication()
                routing {
                    authenticate {
                        get("/whoami") {
                            val principal = call.principal<UserDetailsPrincipal>()!!
                            call.respondText("${principal.name}:${principal.groups.joinToString(",")}")
                        }
                    }
                }
            }
            val response = client.get("/whoami") { header(HttpHeaders.Authorization, "Bearer $bearer") }
            result = response.status to response.bodyAsText()
        }
        return result!!
    }

    "HS256 token with only the layer1 audience is accepted" {
        whoami(token(listOf(AUDIENCE), Algorithm.HMAC256(SECRET))) shouldBe (HttpStatusCode.OK to "alice:engineers")
    }

    "RS256 token from JWKS with layer1 and MCP audiences is accepted" {
        val bearer = token(listOf(AUDIENCE, MCP_RESOURCE), Algorithm.RSA256(publicKey, privateKey), KID)
        whoami(bearer) shouldBe (HttpStatusCode.OK to "alice:engineers")
    }

    "RS256 token without the layer1 audience is rejected" {
        val bearer = token(listOf(MCP_RESOURCE), Algorithm.RSA256(publicKey, privateKey), KID)
        whoami(bearer).first shouldBe HttpStatusCode.Unauthorized
    }

    "RS256 token signed by an unknown key is rejected" {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bearer = token(listOf(AUDIENCE, MCP_RESOURCE),
            Algorithm.RSA256(other.public as RSAPublicKey, other.private as RSAPrivateKey), KID)
        whoami(bearer).first shouldBe HttpStatusCode.Unauthorized
    }
})
