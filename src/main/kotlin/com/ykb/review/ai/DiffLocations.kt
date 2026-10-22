package com.ykb.review.ai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.ykb.review.bitbucket.InlineCommentLocation
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Diff satır konumları (VS Code diffLocations.ts + inlineComments.ts karşılığı).
 * Yalnızca diff hunk'larında gerçekten bulunan satırlar satır içi yoruma uygundur.
 */
object DiffLocations {

    private data class DiffLine(val index: Int, val location: InlineCommentLocation)

    private val HUNK = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
    private val LINE_SPLIT = Regex("\r?\n")
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private fun scan(diff: String): List<DiffLine> {
        val result = mutableListOf<DiffLine>()
        var oldPath = ""
        var newPath = ""
        var oldLine = 0
        var newLine = 0
        var oldRemaining = 0
        var newRemaining = 0
        var inHunk = false
        for ((index, text) in diff.split(LINE_SPLIT).withIndex()) {
            if (text.startsWith("diff --git ")) {
                oldPath = ""
                newPath = ""
                inHunk = false
            } else if (!inHunk && text.startsWith("--- ")) {
                oldPath = headerPath(text.substring(4), "a/", "src://")
                newPath = ""
            } else if (!inHunk && text.startsWith("+++ ")) {
                newPath = headerPath(text.substring(4), "b/", "dst://")
            } else if (text.startsWith("@@ ")) {
                val match = HUNK.find(text)
                inHunk = match != null
                if (match != null) {
                    oldLine = match.groupValues[1].toInt()
                    newLine = match.groupValues[3].toInt()
                    oldRemaining = match.groups[2]?.value?.toInt() ?: 1
                    newRemaining = match.groups[4]?.value?.toInt() ?: 1
                }
            } else if (inHunk) {
                val path = newPath.ifEmpty { oldPath }
                val srcPath = if (oldPath.isNotEmpty() && newPath.isNotEmpty() && oldPath != newPath) oldPath else null
                if (text.startsWith("+") && newRemaining > 0) {
                    if (path.isNotEmpty()) result.add(DiffLine(index, InlineCommentLocation(path, srcPath, newLine, "new", "ADDED")))
                    newLine++
                    newRemaining--
                } else if (text.startsWith("-") && oldRemaining > 0) {
                    if (path.isNotEmpty()) result.add(DiffLine(index, InlineCommentLocation(path, srcPath, oldLine, "old", "REMOVED")))
                    oldLine++
                    oldRemaining--
                } else if (text.startsWith(" ") && oldRemaining > 0 && newRemaining > 0) {
                    if (path.isNotEmpty()) {
                        result.add(DiffLine(index, InlineCommentLocation(path, srcPath, oldLine, "old", "CONTEXT")))
                        result.add(DiffLine(index, InlineCommentLocation(path, srcPath, newLine, "new", "CONTEXT")))
                    }
                    oldLine++
                    newLine++
                    oldRemaining--
                    newRemaining--
                } else if (!text.startsWith("\\")) {
                    inHunk = false
                }
                if (oldRemaining == 0 && newRemaining == 0) inHunk = false
            }
        }
        return result
    }

    /** Git a/ ve b/, Bitbucket Server'ın ham PR diff'i src:// ve dst:// öneklerini kullanır. */
    internal val PATH_PREFIX = Regex("^(?:[ab]/|src://|dst://)")

    internal fun headerPath(value: String, vararg prefixes: String): String {
        var path = value.split('\t')[0]
        if (path.startsWith("\"")) {
            if (!path.endsWith("\"") || path.length < 2) return ""
            path = decodeQuoted(path.substring(1, path.length - 1)) ?: return ""
        }
        if (path == "/dev/null") return ""
        val prefix = prefixes.firstOrNull { path.startsWith(it) } ?: return path
        return path.substring(prefix.length)
    }

    /** Git'in C tarzı kaçışlı (UTF-8 baytları sekizlik) yol biçimini çözer. */
    private fun decodeQuoted(value: String): String? {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                if (next in '0'..'7') {
                    var j = i + 1
                    val digits = StringBuilder()
                    while (j < value.length && digits.length < 3 && value[j] in '0'..'7') {
                        digits.append(value[j]); j++
                    }
                    out.write(digits.toString().toInt(8) and 0xFF)
                    i = j
                    continue
                }
                val mapped = when (next) {
                    'a' -> '\u0007'; 'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'
                    'r' -> '\r'; 't' -> '\t'; 'v' -> '\u000B'; else -> next
                }
                out.write(mapped.toString().toByteArray(StandardCharsets.UTF_8))
                i += 2
                continue
            }
            out.write(c.toString().toByteArray(StandardCharsets.UTF_8))
            i++
        }
        val decoded = String(out.toByteArray(), StandardCharsets.UTF_8)
        return if (decoded.contains('\uFFFD')) null else decoded
    }

    fun diffLineLocations(diff: String): List<InlineCommentLocation> = scan(diff).map { it.location }

    fun changedLineLocations(diff: String): List<InlineCommentLocation> =
        diffLineLocations(diff).filter { it.lineType != "CONTEXT" }

    /** Parçalama hunk başlığını kaybettirmeden önce her satıra gerçek dosya koordinatlarını ekler. */
    fun annotateDiffLines(diff: String): String {
        val lines = diff.split(LINE_SPLIT).toMutableList()
        for ((index, location) in scan(diff)) {
            // Satır başına tek yetkili koordinat; bağlam satırları güncel dosya ile etiketlenir.
            if (location.lineType == "CONTEXT" && location.side == "old") continue
            val file = if (location.side == "old") location.srcPath ?: location.path else location.path
            val coordinates = JsonObject().apply {
                addProperty("file", file)
                addProperty("line", location.line)
                addProperty("side", location.side)
            }
            val original = lines[index]
            lines[index] = "${original[0]}[review-location ${gson.toJson(coordinates)}] ${original.substring(1)}"
        }
        return lines.joinToString("\n")
    }

    /** Bulgunun dosya/satır/taraf bilgisini diff'teki tek bir konuma eşler; belirsizse null. */
    fun findInlineLocation(finding: ReviewFinding, locations: List<InlineCommentLocation>): InlineCommentLocation? {
        val line = finding.line ?: return null
        val file = finding.file.replace('\\', '/').removePrefix("./")
        fun matchesPath(location: InlineCommentLocation, path: String): Boolean =
            location.path == path || (location.side == "old" && location.srcPath == path)
        // Önce birebir repo yolu; a/ ve b/ gerçek klasör adı da olabilir.
        val path = if (locations.any { matchesPath(it, file) }) file else file.replace(PATH_PREFIX, "")
        val matches = locations.filter {
            it.line == line && (finding.side == null || it.side == finding.side) && matchesPath(it, path)
        }
        // Taraf bilgisi eksikse yalnızca diff tek bir konum tanımlıyorsa kurtarılabilir.
        return matches.singleOrNull()
    }
}
