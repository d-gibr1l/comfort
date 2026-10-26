package com.comfort.app.worker

import android.content.Context
import com.comfort.app.R

/** The wrappers' "note" reports about extra commands they left out or changed, as text in the
 * app's language. The flag is quoted as typed; yt-dlp's or gallery-dl's own detail stays English. */
internal object ExtraCommandNotes {

    fun text(context: Context, note: EngineEvent.Note): String {
        val flag = note.flag.orEmpty()
        return when (note.code) {
            "extra_unknown" -> context.getString(R.string.extra_note_unknown, flag)
            "extra_other_engine" -> context.getString(R.string.extra_note_other_engine, flag, note.engine ?: "")
            "extra_not_allowed" -> context.getString(R.string.extra_note_not_allowed, flag)
            "extra_invalid" -> context.getString(R.string.extra_note_invalid, flag, note.detail ?: "")
            "extra_quotes" -> context.getString(R.string.extra_note_quotes)
            "extra_url" -> context.getString(R.string.extra_note_url, flag)
            "extra_key_value" -> context.getString(R.string.extra_note_key_value, flag)
            // A code from a newer script: still say which flag, so it isn't silent.
            else -> context.getString(R.string.extra_note_unknown, flag.ifEmpty { note.code })
        }
    }
}
