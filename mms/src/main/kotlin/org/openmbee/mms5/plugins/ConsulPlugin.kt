package org.openmbee.mms5.plugins

import com.orbitz.consul.Consul
import com.orbitz.consul.model.agent.ImmutableRegistration
import com.orbitz.consul.model.agent.Registration
import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*


fun Application.registerService() {
    install(ConsulPlugin) {
        consulUrl = environment.config.propertyOrNull("consul.service.url")?.getString() ?: "http://localhost:8500"
        consulToken = environment.config.propertyOrNull("consul.service.token")?.getString() ?: ""
        consulServiceName = environment.config.propertyOrNull("consul.service.name")?.getString() ?: "auth-service"
        consulServicePort = environment.config.propertyOrNull("consul.service.port")?.getString()?.toInt() ?: 8080
        consulServiceTags = environment.config.propertyOrNull("consul.service.tags")?.getList() ?: emptyList()
    }

    routing {
        val consulUrl = environment.config.propertyOrNull("consul.service.url")?.getString() ?: "http://localhost:8500"
        val consulToken = environment.config.propertyOrNull("consul.service.token")?.getString() ?: ""
        val consulServiceName = environment.config.propertyOrNull("consul.service.name")?.getString() ?: "auth-service"

        val client = ConsulPlugin.getConsulClient(consulUrl, consulToken)

        get("/healthcheck") {
            client.agentClient().pass(consulServiceName)
            call.respond(hashMapOf("status" to "healthy"))
        }
    }
}

class ConsulPlugin {
    class Config {
        var consulUrl: String = ""
        var consulToken: String = ""
        var consulServiceName: String = ""
        var consulServicePort: Int = 8080
        var consulServiceTags: List<String> = emptyList()

        fun build(): ConsulPlugin = ConsulPlugin()
    }

    companion object Feature : ApplicationFeature<ApplicationCallPipeline, Config, ConsulPlugin> {
        override val key = AttributeKey<ConsulPlugin>("ConsulFeature")

        override fun install(pipeline: ApplicationCallPipeline, configure: Config.() -> Unit): ConsulPlugin {
            val configuration = Config().apply(configure)

            println("Consul Registration starting...")

            val agentClient = getConsulClient(configuration.consulUrl, configuration.consulToken).agentClient()
            val service = ImmutableRegistration.builder()
                .id(configuration.consulServiceName)
                .name(configuration.consulServiceName)
                .port(configuration.consulServicePort)
                .check(Registration.RegCheck.ttl(300L))
                .tags(configuration.consulServiceTags)
                .meta(Collections.singletonMap("version", "1.0"))
                .build()
            agentClient.register(service)
            agentClient.pass(configuration.consulServiceName)
            return configuration.build()
        }

        fun getConsulClient(consulUrl: String, consulToken: String): Consul {
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            return Consul.builder()
                .withUrl(consulUrl)
                .withSslContext(sslContext)
                .withTrustManager(trustAllCerts[0] as X509TrustManager?)
                .withHostnameVerifier(CustomHostnameVerifier)
                .withTokenAuth(consulToken)
                .build()
        }

        fun getAuthService() {

        }
    }
}

// TODO: Remove when unnecessary?
open class CustomHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        return true
    }

    companion object HostnameVerifier : CustomHostnameVerifier()
}

val trustAllCerts = arrayOf<TrustManager>(
    object : X509TrustManager {
        @Throws(CertificateException::class)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return arrayOf()
        }
    }
)
