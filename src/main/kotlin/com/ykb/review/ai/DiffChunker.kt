package com.ykb.review.ai

data class DiffChunk(
    val index: Int,
    val totalChunks: Int,
    val fileNames: List<String>,
    val content: String,
    val lineCount: Int,
    val isHighComplexity: Boolean = false // Smart Router için karmaşıklık bayrağı
)

object DiffChunker {

    private val COMPLEXITY_INDICATORS = setOf(
        "security", "auth", "token", "password", "crypto", "payment",
        "service", "controller", "transaction", "repository", "config"
    )

    fun chunkDiff(rawDiff: String, maxLinesPerChunk: Int = 450): List<DiffChunk> {
        val lines = rawDiff.lines()
        val fileBlocks = mutableListOf<MutableList<String>>()
        var currentBlock = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("diff --git ")) {
                if (currentBlock.isNotEmpty()) {
                    fileBlocks.add(currentBlock)
                    currentBlock = mutableListOf()
                }
            }
            currentBlock.add(line)
        }
        if (currentBlock.isNotEmpty()) {
            fileBlocks.add(currentBlock)
        }

        // Dosya filtreleme (Lock, binary vb. dosyaları ele)
        val validBlocks = fileBlocks.filter { block ->
            val firstLine = block.firstOrNull { it.startsWith("diff --git ") } ?: ""
            val filePath = extractFilePath(firstLine)
            !DiffFilter.shouldIgnoreFile(filePath)
        }

        if (validBlocks.isEmpty()) return emptyList()

        val chunks = mutableListOf<DiffChunk>()
        var currentChunkLines = mutableListOf<String>()
        var currentChunkFiles = mutableListOf<String>()

        for (block in validBlocks) {
            val firstLine = block.firstOrNull { it.startsWith("diff --git ") } ?: ""
            val filePath = extractFilePath(firstLine)

            if (currentChunkLines.size + block.size > maxLinesPerChunk && currentChunkLines.isNotEmpty()) {
                chunks.add(createChunk(chunks.size, currentChunkFiles, currentChunkLines))
                currentChunkLines = mutableListOf()
                currentChunkFiles = mutableListOf()
            }

            currentChunkFiles.add(filePath)
            currentChunkLines.addAll(block)
        }

        if (currentChunkLines.isNotEmpty()) {
            chunks.add(createChunk(chunks.size, currentChunkFiles, currentChunkLines))
        }

        val total = chunks.size
        return chunks.mapIndexed { idx, c ->
            c.copy(index = idx + 1, totalChunks = total)
        }
    }

    private fun createChunk(index: Int, fileNames: List<String>, lines: List<String>): DiffChunk {
        val content = lines.joinToString("\n")
        val isComplex = fileNames.any { path ->
            val lower = path.lowercase()
            COMPLEXITY_INDICATORS.any { lower.contains(it) } || lower.endsWith(".java") || lower.endsWith(".kt")
        } || lines.size > 250

        return DiffChunk(
            index = index + 1,
            totalChunks = 1,
            fileNames = fileNames.distinct(),
            content = content,
            lineCount = lines.size,
            isHighComplexity = isComplex
        )
    }

    private fun extractFilePath(diffGitLine: String): String {
        val parts = diffGitLine.split(" ")
        if (parts.size >= 4) {
            val bPart = parts[3]
            return if (bPart.startsWith("b/")) bPart.substring(2) else bPart
        }
        return "unknown"
    }
}
