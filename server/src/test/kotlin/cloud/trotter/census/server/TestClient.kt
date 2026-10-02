package cloud.trotter.census.server

import cloud.trotter.census.server.auth.RequestSigner
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.Base64

internal suspend fun HttpClient.enrol(id: String, secret: String, schema: String = "uinode.skeleton.v1", version: String = "1.0", bodyId: String = id): HttpResponse =
    post("/v1/enroll") {
        header(HttpHeaders.Authorization, "Bearer $id.$secret")
        contentType(ContentType.Application.Json)
        setBody("{\"installId\":\"$bodyId\",\"appVersion\":\"$version\",\"schemaIds\":[\"$schema\"]}")
    }

internal suspend fun HttpClient.signed(
    clock: Clock, id: String, secret: String, method: HttpMethod, path: String, body: String = "",
    offset: Long = 0, signature: String? = null, signedBody: String = body,
): HttpResponse = request(path) {
    this.method = method
    val timestamp = (clock.now().epochSecond + offset).toString()
    header(HttpHeaders.Authorization, "Bearer $id.$secret")
    header("X-Census-Timestamp", timestamp)
    header("X-Census-Signature", signature ?: RequestSigner.sign(secret, RequestSigner.canonical(method.value, path, timestamp, signedBody.toByteArray())))
    contentType(ContentType.Application.Json)
    if (body.isNotEmpty()) setBody(body)
}

internal suspend fun assertError(response: HttpResponse, status: Int, error: String) {
    assertEquals(status, response.status.value)
    assertEquals("{\"error\":\"$error\"}", response.bodyAsText())
}

internal fun secret(seed: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (seed + it).toByte() })
