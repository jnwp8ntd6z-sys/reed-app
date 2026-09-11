package org.olcbox.app.data.reed

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.ChallengeHandler
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecPolicyCreateSSL
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustSetPolicies

internal actual val reedFrontFallbackSupported: Boolean = true

@Suppress("UNCHECKED_CAST")
internal actual fun HttpClientConfig<*>.configureReedFrontTls() {
    // На iOS движок ktor — только Darwin, конфиг движка — DarwinClientEngineConfig.
    (this as HttpClientConfig<DarwinClientEngineConfig>).engine {
        handleChallenge(reedFrontChallengeHandler)
    }
}

/**
 * Для запросов на IP фронта проверяем цепочку сертификата обычной системной проверкой,
 * но на имя reedapp.ru (SecPolicyCreateSSL с hostname). Остальные хосты — стандартно.
 */
@OptIn(ExperimentalForeignApi::class)
internal val reedFrontChallengeHandler: ChallengeHandler = { _, _, challenge, completionHandler ->
    val space = challenge.protectionSpace
    val trust = space.serverTrust
    if (space.authenticationMethod == NSURLAuthenticationMethodServerTrust &&
        space.host == ReedFront.FRONT_IP && trust != null
    ) {
        val cfHost = CFStringCreateWithCString(null, ReedFront.API_HOST, kCFStringEncodingUTF8)
        val policy = SecPolicyCreateSSL(true, cfHost)
        SecTrustSetPolicies(trust, policy)
        if (cfHost != null) CFRelease(cfHost)
        if (policy != null) CFRelease(policy)
        if (SecTrustEvaluateWithError(trust, null)) {
            completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
        } else {
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    } else {
        completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
    }
}
