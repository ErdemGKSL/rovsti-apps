package dev.rovsti.platform

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * A content provider for tests that serves canned rows and records every call. Register it for an authority with
 * Robolectric (`Robolectric.setupContentProvider(RecordingProvider::class.java, authority)`, or
 * `ShadowContentResolver.registerProviderInternal`).
 *
 * Reads: a table is the uri path without a trailing numeric id (`contacts/5` reads the `contacts` table). [seed]
 * serves fixed rows; [respond] serves rows computed from the [QueryCall] (selection and arguments). Columns the
 * projection asks for and a row lacks are null. Writes are only recorded: [applyBatch] answers a uri with the next id
 * for an insert and a count of 1 for the rest, so back references resolve.
 */
class RecordingProvider : ContentProvider() {
    /** One `query`. */
    data class QueryCall(
        val uri: Uri,
        val projection: List<String>?,
        val selection: String?,
        val args: List<String>?,
        val sortOrder: String?,
    )

    enum class Kind { Insert, Update, Delete, AssertQuery }

    /** One operation of a batch, or one direct write. [values] has back references resolved. */
    data class Op(
        val kind: Kind,
        val uri: Uri,
        val values: ContentValues,
        val selection: String?,
        val args: List<String>?,
    ) {
        val mime: String? get() = values.getAsString("mimetype")
    }

    private val tables = HashMap<String, (QueryCall) -> List<Map<String, Any?>>>()
    private var nextId = 100L

    val queries = ArrayList<QueryCall>()

    /** Every `applyBatch`, in order, each with its operations. */
    val batches = ArrayList<List<Op>>()
    val inserts = ArrayList<Op>()
    val updates = ArrayList<Op>()
    val deletes = ArrayList<Op>()

    fun queriesTo(uri: Uri): List<QueryCall> = queries.filter { key(it.uri) == key(uri) }

    /** Serves [rows] for every query of [uri]'s table. */
    fun seed(
        uri: Uri,
        vararg rows: Map<String, Any?>,
    ) {
        tables[key(uri)] = { rows.toList() }
    }

    /** Serves the rows [rows] computes for each query of [uri]'s table. */
    fun respond(
        uri: Uri,
        rows: (QueryCall) -> List<Map<String, Any?>>,
    ) {
        tables[key(uri)] = rows
    }

    fun clearRecords() {
        queries.clear()
        batches.clear()
        inserts.clear()
        updates.clear()
        deletes.clear()
    }

    private fun key(uri: Uri): String {
        val segments = uri.pathSegments.toMutableList()
        if (segments.lastOrNull()?.toLongOrNull() != null) segments.removeAt(segments.lastIndex)
        return "${uri.authority}/${segments.joinToString("/")}"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val call = QueryCall(uri, projection?.toList(), selection, selectionArgs?.toList(), sortOrder)
        queries += call
        val rows = tables[key(uri)]?.invoke(call).orEmpty()
        val columns = projection ?: rows.firstOrNull()?.keys?.toTypedArray() ?: emptyArray()
        val cursor = MatrixCursor(columns)
        for (row in rows) cursor.addRow(columns.map { row[it] })
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        inserts += Op(Kind.Insert, uri, ContentValues(values ?: ContentValues()), null, null)
        return ContentUris.withAppendedId(uri, nextId++)
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        updates += Op(Kind.Update, uri, ContentValues(values ?: ContentValues()), selection, selectionArgs?.toList())
        return 1
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        deletes += Op(Kind.Delete, uri, ContentValues(), selection, selectionArgs?.toList())
        return 1
    }

    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val results = ArrayList<ContentProviderResult>()
        val recorded = ArrayList<Op>()
        for ((index, op) in operations.withIndex()) {
            val previous = results.toTypedArray()
            val values = op.resolveValueBackReferences(previous, index) ?: ContentValues()
            val selection = selection(op)
            val args = op.resolveSelectionArgsBackReferences(previous, index)?.toList()
            when {
                op.isInsert -> {
                    recorded += Op(Kind.Insert, op.uri, values, null, null)
                    results += ContentProviderResult(ContentUris.withAppendedId(op.uri, nextId++))
                }

                op.isUpdate -> {
                    recorded += Op(Kind.Update, op.uri, values, selection, args)
                    results += ContentProviderResult(1)
                }

                op.isDelete -> {
                    recorded += Op(Kind.Delete, op.uri, ContentValues(), selection, args)
                    results += ContentProviderResult(1)
                }

                else -> {
                    recorded += Op(Kind.AssertQuery, op.uri, values, selection, args)
                    results += ContentProviderResult(1)
                }
            }
        }
        batches += recorded
        return results.toTypedArray()
    }

    /** `ContentProviderOperation` has no getter for its selection; the field name is AOSP's. */
    private fun selection(op: ContentProviderOperation): String? =
        try {
            ContentProviderOperation::class.java
                .getDeclaredField(
                    "mSelection",
                ).apply { isAccessible = true }
                .get(op) as? String
        } catch (_: ReflectiveOperationException) {
            null
        }
}
