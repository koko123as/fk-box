// FK-box: FlClash's mitm.certificate / mitm.generateCertificate answered with
// FK-box's MITM CA (bridge/MitmCertificate: the key stays in the Android
// keystore, only the public certificate is stored). The same CA signs HTTPS
// capture and HTTP rewrite of the sing-box core, so FlClash's certificate page
// and FK-box's HTTP capture show and install one certificate.
package com.follow.clash.core

import com.follow.clash.common.GlobalState
import io.nekohasekai.sfa.bridge.MitmCertificate
import org.json.JSONObject
import java.security.cert.X509Certificate
import java.time.Instant

internal object FkMitm {
    private val store by lazy { MitmCertificate(GlobalState.application) }

    private fun revision(): String = store.snapshot()["revision"] as String

    /** lib/models/core.dart MitmCertificate, or null when no CA exists yet. */
    fun certificate(): JSONObject? {
        val snapshot = store.snapshot()
        if (snapshot["exists"] != true) return null
        return json(store.publicCertificate(snapshot["revision"] as String))
    }

    fun generate(): JSONObject {
        store.generate(revision())
        return certificate() ?: throw CoreMethodError("mitm_error", "The MITM CA was not created")
    }

    private fun json(certificate: X509Certificate): JSONObject = JSONObject()
        .put("pem", MitmCertificate.pem(certificate))
        .put("fingerprint", MitmCertificate.fingerprint(certificate))
        .put("subject", certificate.subjectX500Principal.name)
        .put("not-before", Instant.ofEpochMilli(certificate.notBefore.time).toString())
        .put("not-after", Instant.ofEpochMilli(certificate.notAfter.time).toString())
}
