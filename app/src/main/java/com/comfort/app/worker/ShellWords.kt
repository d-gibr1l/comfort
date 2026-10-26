package com.comfort.app.worker

/** Splits a line into words the way the wrappers do (Python's shlex.split, POSIX mode), so the app
 * can check and show extra commands exactly as the engines will read them. */
internal object ShellWords {

    /** The words, or null where shlex.split raises: an unclosed quote, or a backslash at the end. */
    fun split(text: String): List<String>? {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    if (inWord) { words += word.toString(); word.clear(); inWord = false }
                }
                c == '\\' -> {
                    // Outside quotes a backslash keeps the next character, whatever it is.
                    if (i + 1 >= text.length) return null
                    word.append(text[i + 1]); inWord = true; i++
                }
                c == '\'' -> {
                    // Single quotes keep everything up to the next one, backslashes included.
                    val end = text.indexOf('\'', i + 1)
                    if (end < 0) return null
                    word.append(text, i + 1, end); inWord = true; i = end
                }
                c == '"' -> {
                    // In double quotes a backslash only escapes " and itself (shlex's escapedquotes).
                    var j = i + 1
                    while (true) {
                        if (j >= text.length) return null
                        val d = text[j]
                        if (d == '"') break
                        if (d == '\\' && j + 1 < text.length && (text[j + 1] == '"' || text[j + 1] == '\\')) {
                            word.append(text[j + 1]); j += 2
                        } else {
                            word.append(d); j++
                        }
                    }
                    inWord = true; i = j
                }
                else -> { word.append(c); inWord = true }
            }
            i++
        }
        if (inWord) words += word.toString()
        return words
    }
}
