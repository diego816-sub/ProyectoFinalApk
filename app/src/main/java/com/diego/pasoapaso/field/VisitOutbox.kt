package com.diego.pasoapaso.field

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Encrypted private drafts, isolated by server and account. Pending payloads are immutable. */
class VisitOutbox(context: Context, private val store: FieldStore) {
    private val app = context.applicationContext
    private val scope = store.endpoint + "|" + store.user?.optString("id").orEmpty()
    private val owner = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
    private val directory = File(app.noBackupFilesDir, "visit_outbox/$owner").apply { mkdirs() }
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Visita inválida" }
        return AtomicFile(File(directory, "$id.json"))
    }
    fun read(id: String): JSONObject = synchronized(lock) {
        val target = file(id)
        if (!target.baseFile.exists()) JSONObject().put("state", "draft")
        else JSONObject(store.decryptLocal(String(target.readFully(), Charsets.UTF_8)))
    }
    fun write(id: String, value: JSONObject) = synchronized(lock) {
        val target = file(id)
        val stream = target.startWrite()
        try { stream.write(store.encryptLocal(value.toString()).toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    fun edit(id: String, change: (JSONObject) -> Unit) = synchronized(lock) {
        val entry = read(id)
        check(entry.optString("state") == "draft") { "El resultado ya está en la cola. Revisa su estado antes de editarlo." }
        change(entry); write(id, entry)
    }
    fun enqueue(id: String, payload: JSONObject) = synchronized(lock) {
        check(read(id).optString("state") == "draft") { "Ya existe un envío para esta visita." }
        payload.put("operation_id", UUID.randomUUID().toString())
        write(id, JSONObject().put("state", "pending").put("payload", payload).put("message", "Pendiente de sincronización"))
        VisitSyncService.schedule(app)
    }
    fun entries(): List<Pair<String, JSONObject>> = synchronized(lock) {
        directory.listFiles()?.filter { it.extension == "json" }?.map { it.nameWithoutExtension to read(it.nameWithoutExtension) } ?: emptyList()
    }
    fun retryAfterReview(id: String, version: Int) = synchronized(lock) {
        val entry = read(id)
        check(entry.optString("state") in listOf("conflict", "rejected"))
        entry.getJSONObject("payload").put("version", version).put("operation_id", UUID.randomUUID().toString())
        entry.put("state", "pending").put("message", "Pendiente de sincronización")
        write(id, entry); VisitSyncService.schedule(app)
    }
    fun editRejected(id: String) = synchronized(lock) {
        val entry=read(id)
        check(entry.optString("state")=="rejected")
        val payload=entry.getJSONObject("payload")
        payload.remove("operation_id"); payload.remove("version")
        payload.put("state","draft")
        store.saveDraft(id,payload.optString("notes"))
        write(id,payload)
    }
    /** Returns true only for a transient failure requiring another background attempt. */
    suspend fun sync(): Boolean = syncMutex.withLock {
        if (!store.loggedIn || scope != store.endpoint + "|" + store.user?.optString("id").orEmpty()) return@withLock false
        for ((id, entry) in entries()) {
            if (entry.optString("state") !in listOf("pending", "auth")) continue
            if (scope != store.endpoint + "|" + store.user?.optString("id").orEmpty()) return@withLock false
            try {
                val result = store.request("POST", "/visits/$id/result", entry.getJSONObject("payload"))
                entry.put("state", "sent").put("message", "Recibido por el servidor: ${result.getString("received_at")}")
                // Keep the encrypted result available for review after an ambiguous network response.
                write(id, entry)
            } catch (e: FieldApiException) {
                if (e.status >= 500 || e.status == 429) return@withLock true
                entry.put("state", when(e.status) { 401 -> "auth"; 409 -> "conflict"; else -> "rejected" })
                    .put("message", e.message)
                write(id, entry)
                if (e.status == 401) return@withLock false
            } catch (_: java.io.IOException) { return@withLock true }
        }
        false
    }
    companion object { private val lock = Any(); private val syncMutex = Mutex() }
}
