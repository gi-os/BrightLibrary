package com.lightfastread.parser

object HtmlStripper {
    // Zero-width sentinels emitted around <i>/<em> spans so downstream code can
    // recover italic intent without reparsing HTML. Chosen from C0 controls that
    // never legitimately appear in ebook body text.
    const val ITALIC_START = ''
    const val ITALIC_END = ''

    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6",
        "tr", "td", "th", "section", "article", "header", "footer", "blockquote", "pre"
    )

    private val ITALIC_TAGS = setOf("i", "em", "cite", "dfn", "var")

    private val START_ATTR = Regex("\\bstart\\s*=\\s*\"?(\\d+)", RegexOption.IGNORE_CASE)

    private data class ListState(val ordered: Boolean, var counter: Int)

    private val ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ndash" to "–",
        "mdash" to "—",
        "hellip" to "…",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
    )

    fun stripItalicMarkers(s: String): String {
        if (s.indexOf(ITALIC_START) < 0 && s.indexOf(ITALIC_END) < 0) return s
        return s.filter { it != ITALIC_START && it != ITALIC_END }
    }

    fun strip(html: String): String {
        val out = StringBuilder()
        var i = 0
        var inScriptOrStyle: String? = null
        // Tracks nested <ul>/<ol> so each <li> can emit a bullet for unordered
        // or an auto-incrementing "{n}." for ordered. The bullet/number is
        // written into the output as a standalone token, becoming its own RSVP
        // "word" — that's the visual cue the reader is now inside a list and
        // a new item just started.
        val listStack = ArrayDeque<ListState>()
        var italicDepth = 0
        while (i < html.length) {
            val c = html[i]
            if (inScriptOrStyle != null) {
                val end = "</$inScriptOrStyle"
                val idx = html.indexOf(end, startIndex = i, ignoreCase = true)
                if (idx < 0) break
                i = idx
                inScriptOrStyle = null
                continue
            }
            if (c == '<') {
                val gt = html.indexOf('>', i + 1)
                if (gt < 0) break
                val tagContent = html.substring(i + 1, gt)
                val isClose = tagContent.startsWith("/")
                val tagName = tagContent.trimStart('/').takeWhile { it.isLetterOrDigit() }.lowercase()
                if (tagName == "script" || tagName == "style") {
                    if (!isClose) {
                        inScriptOrStyle = tagName
                    }
                } else if (tagName == "ul" || tagName == "ol") {
                    if (isClose) {
                        listStack.removeLastOrNull()
                        // End-of-list marker: a heavier glyph than the per-item
                        // bullet so the reader gets one final beat signaling
                        // "list closed" before prose resumes. Each closed list
                        // emits its own marker, so nested lists end visibly too.
                        out.append(' ')
                        out.append('\n')
                        out.append('▪')
                        out.append(' ')
                        out.append('\n')
                    } else {
                        val ordered = tagName == "ol"
                        val start = if (ordered) {
                            START_ATTR.find(tagContent)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        } else 1
                        listStack.addLast(ListState(ordered, start))
                        out.append(' ')
                        out.append('\n')
                    }
                } else if (tagName in ITALIC_TAGS) {
                    if (isClose) {
                        if (italicDepth > 0) {
                            italicDepth--
                            if (italicDepth == 0) out.append(ITALIC_END)
                        }
                    } else {
                        if (italicDepth == 0) out.append(ITALIC_START)
                        italicDepth++
                    }
                } else if (tagName == "li" && !isClose) {
                    out.append(' ')
                    out.append('\n')
                    val state = listStack.lastOrNull()
                    if (state != null && state.ordered) {
                        out.append(state.counter.toString())
                        out.append('.')
                        state.counter++
                    } else {
                        out.append('•')
                    }
                    out.append(' ')
                } else if (tagName in BLOCK_TAGS || tagName == "br") {
                    out.append(' ')
                    out.append('\n')
                }
                i = gt + 1
            } else if (c == '&') {
                val semi = html.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 10)) {
                    val ent = html.substring(i + 1, semi)
                    val replacement = decodeEntity(ent)
                    if (replacement != null) {
                        out.append(replacement)
                        i = semi + 1
                        continue
                    }
                }
                out.append(c)
                i++
            } else {
                out.append(c)
                i++
            }
        }
        return collapseWhitespace(out.toString())
    }

    private fun decodeEntity(ent: String): String? {
        if (ent.startsWith("#")) {
            val numeric = ent.substring(1)
            val code = if (numeric.startsWith("x") || numeric.startsWith("X")) {
                numeric.substring(1).toIntOrNull(16)
            } else {
                numeric.toIntOrNull()
            }
            if (code != null && code in 0..0x10FFFF) {
                return String(Character.toChars(code))
            }
            return null
        }
        return ENTITIES[ent.lowercase()]
    }

    private fun collapseWhitespace(s: String): String {
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        var pendingNewline = false
        var anyOutput = false
        for (ch in s) {
            if (ch.isWhitespace()) {
                if (ch == '\n' || ch == '\r') pendingNewline = true
                else pendingSpace = true
            } else {
                if (anyOutput) {
                    if (pendingNewline) sb.append('\n')
                    else if (pendingSpace) sb.append(' ')
                }
                pendingSpace = false
                pendingNewline = false
                sb.append(ch)
                anyOutput = true
            }
        }
        return sb.toString()
    }
}
