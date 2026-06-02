package com.fytbt.phone

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log

data class CallLogEntry(
    val name: String?,
    val number: String,
    val type: Int,        // CallLog.Calls.INCOMING_TYPE / OUTGOING_TYPE / MISSED_TYPE
    val dateMs: Long,
    val count: Int = 1,   // consecutive calls to this number collapsed into one row
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: number
}

data class Contact(
    val name: String,
    val number: String,
)

/**
 * Reads the phone's call history + contacts from the standard Android providers. On this head unit
 * those are populated by the FYT's Bluetooth PBAP client once the phone is paired with "Contact
 * sharing" enabled. All reads catch SecurityException (permission not granted) and any provider
 * quirk, returning an empty list rather than crashing.
 */
object PhoneData {
    private const val TAG = "FytBt"

    fun recents(context: Context, limit: Int = 60): List<CallLogEntry> = runCatching {
        val cols = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
        )
        val raw = ArrayList<CallLogEntry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, cols, null, null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val iNum = c.getColumnIndex(CallLog.Calls.NUMBER)
            val iType = c.getColumnIndex(CallLog.Calls.TYPE)
            val iDate = c.getColumnIndex(CallLog.Calls.DATE)
            // Read a bounded raw window; grouping below collapses it down.
            while (c.moveToNext() && raw.size < 500) {
                val number = c.getString(iNum).orEmpty()
                if (number.isNotBlank()) {
                    raw.add(
                        CallLogEntry(
                            name = c.getString(iName),
                            number = number,
                            type = if (iType >= 0) c.getInt(iType) else 0,
                            dateMs = if (iDate >= 0) c.getLong(iDate) else 0L,
                        )
                    )
                }
            }
        }
        // Collapse consecutive calls to the same number into one row with a count, like a normal
        // dialer (so a run of calls to the same person isn't a wall of identical entries).
        val grouped = ArrayList<CallLogEntry>()
        for (e in raw) {
            val last = grouped.lastOrNull()
            if (last != null && last.number == e.number) {
                grouped[grouped.lastIndex] = last.copy(
                    count = last.count + 1,
                    name = last.name ?: e.name,
                )
            } else {
                grouped.add(e)
            }
        }
        // Resolve names from contacts so recents show people, not raw numbers (the call log's
        // CACHED_NAME is usually empty for PBAP-synced logs). Empty index if no contacts permission.
        val byNumber = contactNameIndex(context)
        grouped.take(limit).map { e ->
            if (e.name.isNullOrBlank()) {
                byNumber[normalizeNumber(e.number)]?.let { e.copy(name = it) } ?: e
            } else e
        }
    }.getOrElse {
        Log.w(TAG, "recents read failed: ${it.javaClass.simpleName}: ${it.message}")
        emptyList()
    }

    /** number (last-10-digits) -> contact name, for labelling call-log entries. */
    private fun contactNameIndex(context: Context): Map<String, String> {
        val map = HashMap<String, String>()
        contacts(context).forEach { c ->
            val key = normalizeNumber(c.number)
            if (key.isNotBlank()) map.putIfAbsent(key, c.name)
        }
        return map
    }

    /** Compare by the last 10 digits, so "+1-425-286-0793" matches "+14252860793" etc. */
    private fun normalizeNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun contacts(context: Context): List<Contact> = runCatching {
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        // Distinct-ish: skip duplicate name+number pairs the PBAP sync sometimes produces.
        val seen = HashSet<String>()
        val out = ArrayList<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { c ->
            val iName = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val iNum = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val name = c.getString(iName).orEmpty()
                val number = c.getString(iNum).orEmpty()
                if (name.isNotBlank() || number.isNotBlank()) {
                    val key = "$name|$number"
                    if (seen.add(key)) out.add(Contact(name.ifBlank { number }, number))
                }
            }
        }
        out
    }.getOrElse {
        Log.w(TAG, "contacts read failed: ${it.javaClass.simpleName}: ${it.message}")
        emptyList()
    }
}
