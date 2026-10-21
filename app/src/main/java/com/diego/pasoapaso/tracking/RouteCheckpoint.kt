package com.diego.pasoapaso.tracking

import android.content.Context
import android.util.AtomicFile
import com.diego.pasoapaso.field.FieldStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** An encrypted atomic checkpoint, separate from explicitly saved routes. */
class RouteCheckpoint(context: Context) {
    private val store=FieldStore(context)
    private val identity=store.endpoint+"|"+store.user?.optString("id").orEmpty()
    private val hash=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    private val file=AtomicFile(File(context.noBackupFilesDir,"route_$hash.json"))
    private val lock=Any()
    private var revision=0L
    fun ticket(): Long = synchronized(lock) { revision }
    fun write(ticket: Long, data: JSONObject) = synchronized(lock) {
        if(ticket!=revision) return@synchronized
        val output=file.startWrite()
        try { output.write(store.encryptLocal(data.toString()).toByteArray()); file.finishWrite(output) }
        catch(e: Exception) { file.failWrite(output); throw e }
    }
    fun read(): JSONObject? = synchronized(lock) {
        if(!file.baseFile.exists()) null else JSONObject(store.decryptLocal(String(file.readFully(),Charsets.UTF_8)))
    }
    fun clear() = synchronized(lock) { revision++; file.delete() }
    companion object {
        fun encode(id: String, owner: String?, session: TrackingSession, elapsed: Long): JSONObject {
            val points=JSONArray()
            session.points.forEach { p -> points.put(JSONArray().put(p.fix.latitude).put(p.fix.longitude).put(p.fix.accuracy.toDouble()).put(p.fix.recordedAt).put(p.fix.elapsedMs).put(p.segment)) }
            return JSONObject().put("id",id).put("owner",owner).put("started",session.startedAt).put("distance",session.distanceMeters)
                .put("duration",session.durationMs(elapsed)).put("points",points)
        }
        fun points(data: JSONObject): List<RoutePoint> {
            val rows=data.getJSONArray("points")
            return (0 until rows.length()).map { i -> val p=rows.getJSONArray(i)
                RoutePoint(GpsFix(p.getDouble(0),p.getDouble(1),p.getDouble(2).toFloat(),p.getLong(3),p.getLong(4)),p.getInt(5)) }
        }
    }
}
