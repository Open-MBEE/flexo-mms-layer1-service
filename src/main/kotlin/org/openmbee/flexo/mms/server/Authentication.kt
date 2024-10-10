package org.openmbee.flexo.mms.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun Application.configureAuthentication(environment: ApplicationEnvironment) {
    authentication {
        jwt {
            val jwtAudience = environment.config.property("jwt.audience").getString()
            val issuer = environment.config.property("jwt.domain").getString()
            val secret = environment.config.property("jwt.secret").getString()
            realm = environment.config.property("jwt.realm").getString()

            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(jwtAudience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience)) {
                    UserDetailsPrincipal(
                        credential.payload.claims["username"]?.asString() ?: "",
                        credential.payload.claims["groups"]?.asList("".javaClass) ?: emptyList()
                    )
                } else null
            }
        }
    }
}

data class UserDetailsPrincipal(val name: String, val groups: List<String>) : Principal
