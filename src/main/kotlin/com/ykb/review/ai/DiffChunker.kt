package com.ykb.review.ai

data class DiffChunk(
    val index: Int,        // 0 tabanlı
    val total: Int,
    val files: List<String>,
    val content: String,
) {
    val lineCount: Int get() = content.count { it == '\n' } + 1
}

/** VS Code diffChunker.ts karşılığı: mümkünse dosyaları bütün tutar, çok büyük dosyaları sınırlı parçalara böler. */
object DiffChunker {

    private data class Section(val files: List<String>, val content: String)

    private fun fileSections(diff: String): List<Section> {
        val starts = DiffSections.starts(diff)
        if (starts.isEmpty()) return listOf(Section(emptyList(), diff))
        val sections = mutableListOf<Section>()
        if (starts[0] > 0 && diff.substring(0, starts[0]).isNotBlank()) {
            sections.add(Section(emptyList(), diff.substring(0, starts[0])))
        }
        starts.forEachIndexed { index, start ->
            val content = diff.substring(start, starts.getOrNull(index + 1) ?: diff.length)
            sections.add(Section(listOfNotNull(DiffSections.fileFromHeader(content)), content))
        }
        return sections
    }

    private fun splitOversized(section: Section, maxChunkChars: Int): List<Section> {
        if (section.content.length <= maxChunkChars) return listOf(section)
        val result = mutableListOf<Section>()
        var cursor = 0
        val content = section.content
        while (cursor < content.length) {
            var end = minOf(cursor + maxChunkChars, content.length)
            if (end < content.length) {
                val lastNewline = content.lastIndexOf('\n', end)
                if (lastNewline > cursor) end = lastNewline + 1
            }
            result.add(Section(section.files, content.substring(cursor, end)))
            cursor = end
        }
        return result
    }

    fun chunkDiff(diff: String, maxChunkChars: Int = 60_000, includeLineLocations: Boolean = false): List<DiffChunk> {
        require(maxChunkChars > 0) { "maxChunkChars pozitif bir tam sayı olmalıdır." }
        if (diff.isBlank()) return emptyList()
        val filtered = DiffFilter.filterDiff(diff).filteredDiff
        if (filtered.isBlank()) return emptyList()

        val source = if (includeLineLocations) DiffLocations.annotateDiffLines(filtered) else filtered
        val units = fileSections(source).flatMap { splitOversized(it, maxChunkChars) }

        val groups = mutableListOf<Section>()
        var currentFiles = mutableListOf<String>()
        var current = StringBuilder()
        for (unit in units) {
            if (current.isNotEmpty() && current.length + unit.content.length > maxChunkChars) {
                groups.add(Section(currentFiles, current.toString()))
                currentFiles = mutableListOf()
                current = StringBuilder()
            }
            current.append(unit.content)
            currentFiles.addAll(unit.files)
        }
        if (current.isNotEmpty()) groups.add(Section(currentFiles, current.toString()))

        return groups.mapIndexed { index, group ->
            DiffChunk(index = index, total = groups.size, files = group.files.distinct(), content = group.content)
        }
    }
}
