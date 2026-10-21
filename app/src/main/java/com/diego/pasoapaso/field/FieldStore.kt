package com.diego.pasoapaso.field

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class FieldApiException(val status: Int, message: String) : Exception(message)

/** Every call is explicit: no fake accounts, automatic role elevation or silent demo fallback. */
class FieldStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("nexo_session", Context.MODE_PRIVATE)
    val endpoint: String get() = SERVER_URL
    init {
        // Never send a token cached for a local/previous server to the cloud host.
        if(prefs.getString("endpoint",null)!=SERVER_URL) {
            prefs.edit().remove("user").remove("token").remove("snapshot")
                .putString("endpoint",SERVER_URL).apply()
        }
    }
    val user: JSONObject? get() = prefs.getString("user", null)?.let(::JSONObject)
    val loggedIn: Boolean get() = user != null && prefs.contains("token")
    val coordinator: Boolean get() = user?.optString("role") == "coordinator"
    val cached: JSONObject? get() = prefs.getString("snapshot", null)?.let(::JSONObject)

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nexo_session_key", null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nexo_session_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    internal fun encryptLocal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    internal fun decryptLocal(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    private fun token(): String = prefs.getString("token", null)?.let {
        try { decryptLocal(it) } catch (_: Exception) { throw FieldApiException(401, "Vuelve a iniciar sesión.") }
    }.orEmpty()

    suspend fun login(identifier: String, password: String) {
        clearSession()
        val health=request("GET","/health")
        if(health.optInt("api")<4) throw FieldApiException(426,"Actualiza también el servidor a NexoVisita 1.9 antes de entrar con esta versión.")
        val result = request("POST", "/login", JSONObject().put("identifier", identifier).put("password", password))
        prefs.edit().putString("user", result.getJSONObject("user").toString())
            .putString("token", encryptLocal(result.getString("token"))).apply()
    }

    suspend fun refresh(): JSONObject = request("GET", "/snapshot").also {
        prefs.edit().putString("snapshot", it.toString()).apply()
    }
    fun clearSession() { context.stopService(android.content.Intent(context, JourneyService::class.java)); prefs.edit().remove("user").remove("token").remove("snapshot").apply() }
    suspend fun logout() { try { request("POST", "/logout", JSONObject()) } finally { clearSession() } }
    fun draft(id: String): String {
        val name="draft_${user?.optString("id")}_$id"
        return prefs.getString("secure_$name", null)?.let(::decryptLocal) ?: prefs.getString(name, "").orEmpty()
    }
    fun saveDraft(id: String, value: String) { prefs.edit().putString("secure_draft_${user?.optString("id")}_$id", encryptLocal(value)).remove("draft_${user?.optString("id")}_$id").apply() }
    fun routeIds(): List<String> = prefs.getStringSet("routes_${user?.optString("id")}", emptySet())!!.toList()
    fun ownRoute(id: String, owner: String?) {
        if (owner.isNullOrBlank()) return
        val name = "routes_$owner"
        check(prefs.edit().putStringSet(name, prefs.getStringSet(name, emptySet())!!.toSet() + id).commit()) { "No se pudo guardar el propietario del recorrido." }
    }
    suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI(endpoint + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = if(path.endsWith("/journey")) 20000 else 180000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (path !in setOf("/login", "/activate/request", "/activate/confirm", "/password/request", "/password/confirm")) connection.setRequestProperty("Authorization", "Bearer ${token()}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = try { JSONObject(raw) } catch (_: Exception) {
                throw FieldApiException(503, "El servidor está iniciando o no está disponible. Espera un minuto y vuelve a intentarlo.")
            }
            if (code !in 200..299) throw FieldApiException(code, result.optString("error", "Respuesta del servidor: $code"))
            result
        } catch (_: java.net.SocketTimeoutException) {
            throw FieldApiException(503, "El servidor tardó demasiado en responder. Espera un minuto y vuelve a intentarlo. Los borradores pendientes se conservan.")
        } finally { connection.disconnect() }
    }
    companion object {
        const val SERVER_URL = "https://nexovisita-api.onrender.com"
        private val keyLock=Any()
    }
}
