package com.ykb.review.local

import java.nio.file.Path
import java.nio.file.Paths

data class LocalReviewTarget(
    val paths: List<String>,
    val firstSelectedLine: Int? = null,
    val lastSelectedLine: Int? = null,
)

data class RepositoryReviewScope(
    val root: String,
    val relativePaths: List<String>,
    val extraInstructions: String? = null,
)

/** Seçili IDE öğelerini ait oldukları Git kökü ve güvenli Git pathspec'lerine dönüştürür. */
object LocalReviewScopeResolver {
    fun resolve(roots: List<String>, target: LocalReviewTarget?): List<RepositoryReviewScope> {
        val normalizedRoots = roots.map { Paths.get(it).toAbsolutePath().normalize() }.distinct()
        if (target == null || target.paths.isEmpty()) {
            return normalizedRoots.map { RepositoryReviewScope(it.toString(), emptyList()) }
        }

        val selected = target.paths.map { Paths.get(it).toAbsolutePath().normalize() }.distinct()
        val byRoot = linkedMapOf<Path, MutableList<String>>()
        val fullRepositoryRoots = mutableSetOf<Path>()
        for (path in selected) {
            val root = normalizedRoots
                .filter { path.startsWith(it) }
                .maxByOrNull { it.nameCount }
                ?: continue
            val relative = root.relativize(path).toString().replace('\\', '/')
            val paths = byRoot.getOrPut(root) { mutableListOf() }
            if (relative.isEmpty()) {
                paths.clear()
                fullRepositoryRoots.add(root)
            } else if (root !in fullRepositoryRoots) {
                paths.add(relative)
            }
        }

        return byRoot.map { (root, relativePaths) ->
            val uniquePaths = if (root in fullRepositoryRoots) emptyList() else relativePaths.distinct()
            val instructions = selectionInstructions(target, root, uniquePaths)
            RepositoryReviewScope(root.toString(), uniquePaths, instructions)
        }
    }

    private fun selectionInstructions(
        target: LocalReviewTarget,
        root: Path,
        relativePaths: List<String>,
    ): String? {
        val first = target.firstSelectedLine ?: return null
        val last = target.lastSelectedLine ?: return null
        if (target.paths.size != 1 || relativePaths.size != 1) return null
        val selected = Paths.get(target.paths.single()).toAbsolutePath().normalize()
        if (!selected.startsWith(root)) return null
        val file = relativePaths.single()
        return "Bu yerel incelemenin kapsamı ${jsonString(file)} dosyasındaki $first-$last. satırlardır. " +
            "Yalnızca bu satır aralığına doğrudan dokunan değişiklikler için bulgu üret; " +
            "dosyanın diğer bölümlerini sadece bağlam olarak kullan."
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
