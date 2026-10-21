package com.diego.pasoapaso.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.diego.pasoapaso.tracking.GpsFix
import com.diego.pasoapaso.tracking.RoutePoint

data class Walk(
    val id: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val distanceMeters: Double
)

data class WalkDetail(val walk: Walk, val points: List<RoutePoint>)

/** Ejecutar sus métodos desde Dispatchers.IO. Las consultas usan parámetros enlazados. */
class WalkDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "paso_a_paso.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE walks (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, started_at INTEGER NOT NULL,
            ended_at INTEGER NOT NULL, duration_ms INTEGER NOT NULL, distance_m REAL NOT NULL
        )""")
        db.execSQL("""CREATE TABLE route_points (
            walk_id TEXT NOT NULL REFERENCES walks(id) ON DELETE CASCADE,
            sequence INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
            accuracy REAL NOT NULL, recorded_at INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL,
            segment INTEGER NOT NULL, PRIMARY KEY(walk_id, sequence)
        )""")
        db.execSQL("CREATE INDEX walks_date ON walks(started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Agregar migraciones incrementales al cambiar la versión; no borrar los recorridos.
        error("Falta una migración de $oldVersion a $newVersion")
    }

    fun listWalks(): List<Walk> = readableDatabase.rawQuery(
        "SELECT id,name,started_at,ended_at,duration_ms,distance_m FROM walks ORDER BY started_at DESC", null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Walk(cursor.getString(0), cursor.getString(1),
                cursor.getLong(2), cursor.getLong(3), cursor.getLong(4), cursor.getDouble(5)))
        }
    }

    fun detail(id: String): WalkDetail? {
        val walk = readableDatabase.rawQuery(
            "SELECT id,name,started_at,ended_at,duration_ms,distance_m FROM walks WHERE id=?", arrayOf(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Walk(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3),
                cursor.getLong(4), cursor.getDouble(5))
        }
        val points = readableDatabase.rawQuery(
            "SELECT latitude,longitude,accuracy,recorded_at,elapsed_ms,segment FROM route_points WHERE walk_id=? ORDER BY sequence", arrayOf(id)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(RoutePoint(GpsFix(cursor.getDouble(0), cursor.getDouble(1),
                    cursor.getFloat(2), cursor.getLong(3), cursor.getLong(4)), cursor.getInt(5)))
            }
        }
        return WalkDetail(walk, points)
    }

    fun save(detail: WalkDetail) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val walk = detail.walk
            db.insertOrThrow("walks", null, ContentValues().apply {
                put("id", walk.id); put("name", walk.name); put("started_at", walk.startedAt)
                put("ended_at", walk.endedAt); put("duration_ms", walk.durationMs); put("distance_m", walk.distanceMeters)
            })
            detail.points.forEachIndexed { index, point ->
                db.insertOrThrow("route_points", null, ContentValues().apply {
                    put("walk_id", walk.id); put("sequence", index)
                    put("latitude", point.fix.latitude); put("longitude", point.fix.longitude)
                    put("accuracy", point.fix.accuracy); put("recorded_at", point.fix.recordedAt)
                    put("elapsed_ms", point.fix.elapsedMs); put("segment", point.segment)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun rename(id: String, name: String) {
        require(name.isNotBlank() && name.length <= 60)
        check(writableDatabase.update("walks", ContentValues().apply { put("name", name.trim()) }, "id=?", arrayOf(id)) == 1)
    }

    fun delete(id: String) { check(writableDatabase.delete("walks", "id=?", arrayOf(id)) == 1) }

    companion object {
        @Volatile private var instance: WalkDatabase? = null
        fun get(context: Context): WalkDatabase = instance ?: synchronized(this) {
            instance ?: WalkDatabase(context).also { instance = it }
        }
    }
}
