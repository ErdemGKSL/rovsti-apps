package dev.rosty.platform.sms

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * A small SQLite stand-in for the Telephony provider, for tests that need rows to be stored and selections, sort
 * orders and `LIMIT`s to be honoured (unlike [dev.rosty.platform.RecordingProvider], which serves canned rows). Register
 * one instance per authority with Robolectric:
 * `Robolectric.setupContentProvider(TelephonyTestProvider::class.java, "sms")` and the same for `"mms"`.
 *
 * Tables: `content://sms` (and `/<id>`); `content://mms` (and `/<id>`), `content://mms/part` (and `/<id>`),
 * `content://mms/<id>/addr`. An SMS insert without a `thread_id` gets one per address, as the real provider does. A part
 * file is never served: [openFile] counts the attempt in [filesOpened] and throws [FileNotFoundException].
 */
class TelephonyTestProvider : ContentProvider() {
    private lateinit var db: SQLiteDatabase
    private val threads = HashMap<String, Long>()

    /** How many times a part file was asked for. */
    var filesOpened = 0
        private set

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, address TEXT, body TEXT, " +
                "date INTEGER, type INTEGER DEFAULT 1, read INTEGER DEFAULT 0, seen INTEGER DEFAULT 0, " +
                "sub_id INTEGER DEFAULT -1, status INTEGER DEFAULT -1)",
        )
        db.execSQL(
            "CREATE TABLE pdu (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, date INTEGER, " +
                "msg_box INTEGER DEFAULT 1, read INTEGER DEFAULT 0, seen INTEGER DEFAULT 0, sub_id INTEGER DEFAULT -1)",
        )
        db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY AUTOINCREMENT, mid INTEGER, ct TEXT, text TEXT)")
        db.execSQL(
            "CREATE TABLE addr (_id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER, address TEXT, type INTEGER)",
        )
        return true
    }

    /** The table of [uri], and the `_id` or `msg_id` it narrows to. */
    private data class Target(
        val table: String,
        val column: String? = null,
        val id: Long? = null,
    )

    private fun target(uri: Uri): Target {
        val s = uri.pathSegments
        val first = s.getOrNull(0)
        return when {
            uri.authority == "sms" && s.isEmpty() -> {
                Target("sms")
            }

            uri.authority == "sms" && s.size == 1 && first?.toLongOrNull() != null -> {
                Target(
                    "sms",
                    "_id",
                    first.toLong(),
                )
            }

            uri.authority == "mms" && s.isEmpty() -> {
                Target("pdu")
            }

            uri.authority == "mms" && s == listOf("part") -> {
                Target("part")
            }

            uri.authority == "mms" && s.size == 2 && first == "part" -> {
                Target("part", "_id", s[1].toLong())
            }

            uri.authority == "mms" && s.size == 1 && first?.toLongOrNull() != null -> {
                Target(
                    "pdu",
                    "_id",
                    first.toLong(),
                )
            }

            uri.authority == "mms" && s.size == 2 && s[1] == "addr" -> {
                Target("addr", "msg_id", s[0].toLong())
            }

            else -> {
                throw IllegalArgumentException("no table for $uri")
            }
        }
    }

    private fun where(
        t: Target,
        selection: String?,
        args: Array<out String>?,
    ): Pair<String?, Array<String>?> {
        val own = t.column?.let { "$it = ${t.id}" }
        val sel = listOfNotNull(own, selection?.let { "($it)" }).joinToString(" AND ").ifEmpty { null }
        return sel to args?.map { it }?.toTypedArray()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val t = target(uri)
        val (sel, args) = where(t, selection, selectionArgs)
        return db.query(t.table, projection?.map { it }?.toTypedArray(), sel, args, null, null, sortOrder)
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        val t = target(uri)
        val row = ContentValues(values ?: ContentValues())
        if (t.table == "addr") row.put("msg_id", t.id)
        if (t.table == "sms" && !row.containsKey("thread_id")) {
            val address = row.getAsString("address").orEmpty()
            row.put("thread_id", threads.getOrPut(address) { threads.size + 1L })
        }
        val id = db.insertOrThrow(t.table, null, row)
        return ContentUris.withAppendedId(uri, id)
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val t = target(uri)
        val (sel, args) = where(t, selection, selectionArgs)
        return db.update(t.table, values ?: ContentValues(), sel, args)
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val t = target(uri)
        val (sel, args) = where(t, selection, selectionArgs)
        return db.delete(t.table, sel, args)
    }

    override fun getType(uri: Uri): String? = null

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        filesOpened++
        throw FileNotFoundException("no part file in tests: $uri")
    }
}
