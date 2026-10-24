package dev.scrolltext.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import dev.scrolltext.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class JobConfig(val uri: String, val title: String, val crop: Crop, val startMs: Long, val endMs: Long, val formulas: Boolean = true)
data class ProcessingCheckpoint(
    val stage: String = "video", val timeMs: Long, val offset: Double, val stepMs: Long,
    val lastOcrTimeMs: Long, val lastOcrOffset: Double, val lastQuality: Double,
)
data class ArticleSummary(val id: String, val title: String, val created: Long, val status: String,
    val lineCount: Int, val reviewCount: Int, val preview: String)
data class Article(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    val created: Long = System.currentTimeMillis(),
    var status: String = "processing",
    val config: JobConfig,
    var lines: List<ArticleLine> = emptyList(),
    var frames: Int = 0,
    var duplicates: Int = 0,
    var message: String = "",
    var metrics: Map<String, Long> = emptyMap(),
    var checkpoint: ProcessingCheckpoint? = null,
)

class ArticleStore(private val context: Context) {
    private companion object { val fileLock = Any() }
    private val root = File(context.filesDir, "articles").apply { mkdirs() }
    fun folder(id: String): File { require(id.matches(Regex("[a-fA-F0-9-]{36}"))); return File(root, id).apply { mkdirs() } }
    fun source(id: String, name: String): File { require(name.matches(Regex("[a-zA-Z0-9_-]+\\.(jpg|png)"))); return File(folder(id), name) }

    fun save(article: Article) = synchronized(fileLock) {
        writeAtomic(File(folder(article.id), "article.json"), toJson(article))
        writeAtomic(File(folder(article.id), "summary.json"), summaryJson(article))
    }
    private fun writeAtomic(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun readAtomic(file: File) = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun summaryJson(a: Article) = JSONObject().put("id", a.id).put("title", a.title).put("created", a.created)
        .put("status", a.status).put("lineCount", a.lines.size).put("reviewCount", a.lines.count { it.needsReview })
        .put("preview", a.lines.firstOrNull { it.kind == BlockKind.TEXT && !ArticleExport.hasMath(it) }?.text?.take(100) ?: "")

    fun saveSource(id: String, name: String, bitmap: Bitmap, box: Box) {
        val pad = if (name.endsWith(".png")) 0 else 8
        val left = (box.left - pad).toInt().coerceIn(0, bitmap.width - 1)
        val top = (box.top - pad).toInt().coerceIn(0, bitmap.height - 1)
        val right = (box.right + pad).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom + pad).toInt().coerceIn(top + 1, bitmap.height)
        val cut = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        try { source(id, name).outputStream().use { cut.compress(if (pad == 0) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 93, it) } }
        finally { if (cut !== bitmap) cut.recycle() }
    }

    fun load(id: String): Article = synchronized(fileLock) { parse(readAtomic(File(folder(id), "article.json"))) }
    // Only a small catalog is loaded on the home screen, even for many long articles.
    // Existing v0.1/v0.2 documents migrate lazily; an interrupted summary write is rebuilt.
    fun list(): List<ArticleSummary> = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
        runCatching { synchronized(fileLock) {
            val file = File(dir, "summary.json"); val body = File(dir, "article.json")
            val j = if (file.exists() && file.lastModified() >= body.lastModified()) runCatching { readAtomic(file) }.getOrNull() else null
            val summary = j ?: summaryJson(load(dir.name)).also { writeAtomic(file, it) }
            ArticleSummary(summary.getString("id"), summary.getString("title"), summary.getLong("created"), summary.getString("status"),
                summary.getInt("lineCount"), summary.getInt("reviewCount"), summary.optString("preview"))
        } }.getOrNull()
    }?.sortedByDescending { it.created } ?: emptyList()

    /** Call only after the article referencing the current sources has been durably saved. */
    fun pruneSources(article: Article) {
        val keep = article.lines.flatMap { listOf(it.source) + it.formulaParts.map { p -> p.source } }.toSet()
        folder(article.id).listFiles()?.filter { it.extension in listOf("jpg", "png") && it.name !in keep }?.forEach { it.delete() }
    }

    fun exportName(article: Article, format: String): String {
        val name = article.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(60).ifBlank { "文章" }
        return "拾语-$name.$format"
    }

    fun export(article: Article, format: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val dest = File(File(dir, article.id).apply { mkdirs() }, exportName(article, format))
        when (format) {
            "md" -> dest.writeText(ArticleExport.markdown(article.title, article.lines))
            "txt" -> dest.writeText(article.title + "\n\n" + (if (article.lines.any { it.needsReview && ArticleExport.hasMath(it) }) "【公式为未经校对的 LaTeX 识别候选，请核对原图】\n\n" else "") + ArticleExport.paragraphs(article.lines).joinToString("\n\n"))
            "zip" -> ZipOutputStream(dest.outputStream()).use { zip ->
                fun bytes(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
                bytes("article.md", ArticleExport.markdown(article.title, article.lines).toByteArray())
                bytes("article.html", ArticleExport.html(article.title, article.lines, sourceUrl = { line ->
                    val name = readingSource(line)
                    if (name.isNotEmpty() && source(article.id, name).exists()) "sources/$name" else null
                }).toByteArray())
                bytes("evidence.json", toJson(article).also { it.getJSONObject("config").remove("uri") }.toString(2).toByteArray())
                article.lines.flatMap { listOf(it.source) + it.formulaParts.map { p -> p.source } }.filter { it.isNotEmpty() }.distinct().forEach { name ->
                    val f = source(article.id, name); if (f.exists()) bytes("sources/$name", f.readBytes())
                }
                fun addAssets(path: String) {
                    context.assets.list(path)?.forEach { name ->
                        val full = "$path/$name"
                        if (context.assets.list(full)?.isNotEmpty() == true) addAssets(full)
                        else context.assets.open(full).use { bytes(full.removePrefix("web/"), it.readBytes()) }
                    }
                }
                addAssets("web/katex")
            }
            else -> error("Unsupported export format")
        }
        return dest
    }

    fun readingSource(line: ArticleLine): String = if (line.kind == BlockKind.FORMULA && line.formulaParts.size == 1) line.formulaParts[0].source else line.source

    /** Read an exported ScrollText bundle into a new ID; never trust its paths, HTML or URI. */
    fun importArchive(input: InputStream): Article {
        val stage = File(context.cacheDir, "article-import-${UUID.randomUUID()}").apply { check(mkdirs()) }
        var destination: File? = null
        var committed = false
        try {
            var total = 0L
            val seen = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(seen.add(name) && seen.size <= 10000) { "阅读包包含重复或过多文件" }
                    require(!name.startsWith('/') && !name.contains('\\') && !name.contains(':') &&
                        name.split('/').none { it == ".." || it == "." }) { "阅读包路径无效" }
                    val sourceName = name.removePrefix("sources/")
                    val source = name.startsWith("sources/") && sourceName.matches(Regex("[a-zA-Z0-9_-]+\\.(jpg|png)"))
                    require(!name.startsWith("sources/") || entry.isDirectory || source) { "原图名称无效" }
                    val target = when {
                        name == "evidence.json" -> File(stage, name)
                        source -> File(stage, sourceName)
                        else -> null // Render with our own HTML and bundled KaTeX only.
                    }
                    var size = 0L
                    val output = target?.outputStream()
                    try {
                        val buffer = ByteArray(32768)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count; total += count
                            require(size <= 32L * 1024 * 1024 && total <= 512L * 1024 * 1024) { "阅读包过大，请按文章分开导入" }
                            output?.write(buffer, 0, count)
                        }
                    } finally { output?.close() }
                    zip.closeEntry()
                }
            }
            val evidence = File(stage, "evidence.json")
            require(evidence.exists() && evidence.length() <= 16L * 1024 * 1024) { "请选择卷读导出的完整 ZIP 阅读包" }
            val json = JSONObject(evidence.readText(Charsets.UTF_8))
            require(json.optInt("schema") in 1..3) { "暂不支持此阅读包版本" }
            val id = UUID.randomUUID().toString()
            json.put("id", id)
            json.getJSONObject("config").put("uri", "")
            json.remove("checkpoint")
            val original = parse(json)
            val imported = original.copy(status = "imported", message =
                if (original.status in listOf("complete", "imported")) original.message else "导入的是未完成的文章草稿，续提取需重新导入原录屏。", checkpoint = null)
            require(imported.config.crop.valid()) { "阅读包选区无效" }
            require(imported.lines.size <= 100000 && imported.lines.map { it.id }.distinct().size == imported.lines.size) { "文章行数据无效" }
            val sources = imported.lines.flatMap { listOf(it.source) + it.formulaParts.map { p -> p.source } }
                .filter { it.isNotEmpty() }.distinct()
            for (name in sources) {
                require(name.matches(Regex("[a-zA-Z0-9_-]+\\.(jpg|png)")) && File(stage, name).isFile) { "阅读包缺少原图：$name" }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(File(stage, name).path, bounds)
                require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 24000000) { "原图尺寸无效" }
            }
            imported.lines.forEach { line ->
                require(listOf(line.box.left, line.box.top, line.box.right, line.box.bottom, line.confidence).all { it.isFinite() }) { "文章坐标无效" }
            }
            require(root.usableSpace > sources.sumOf { File(stage, it).length() } + 150L * 1024 * 1024) { "空间不足，请先释放存储" }
            synchronized(fileLock) {
                destination = folder(id)
                sources.forEach { File(stage, it).copyTo(source(id, it), overwrite = false) }
                save(imported)
            }
            committed = true
            return imported
        } finally {
            stage.deleteRecursively()
            if (!committed) destination?.deleteRecursively() // Only the newly allocated UUID folder.
        }
    }

    fun toJson(a: Article): JSONObject = JSONObject().put("schema", 3).put("id", a.id).put("title", a.title).put("created", a.created)
        .put("status", a.status).put("frames", a.frames).put("duplicates", a.duplicates).put("message", a.message)
        .put("metrics", JSONObject(a.metrics))
        .put("checkpoint", a.checkpoint?.let { c -> JSONObject().put("stage", c.stage).put("timeMs", c.timeMs)
            .put("offset", c.offset).put("stepMs", c.stepMs).put("lastOcrTimeMs", c.lastOcrTimeMs)
            .put("lastOcrOffset", c.lastOcrOffset).put("lastQuality", c.lastQuality) })
        .put("config", JSONObject().put("uri", a.config.uri).put("title", a.config.title).put("startMs", a.config.startMs)
            .put("endMs", a.config.endMs).put("formulas", a.config.formulas).put("crop", JSONArray(listOf(a.config.crop.left, a.config.crop.top, a.config.crop.right, a.config.crop.bottom))))
        .put("lines", JSONArray(a.lines.map { l -> JSONObject().put("id", l.id).put("text", l.text)
            .put("box", JSONArray(listOf(l.box.left, l.box.top, l.box.right, l.box.bottom))).put("confidence", l.confidence)
            .put("source", l.source).put("timeMs", l.timeMs).put("kind", l.kind.name).put("observations", l.observations)
            .put("needsReview", l.needsReview).put("clipped", l.clipped).put("edited", l.edited).put("sourceQuality", l.sourceQuality)
            .put("formulaParts", JSONArray(l.formulaParts.map { p -> JSONObject().put("marker", p.marker).put("source", p.source)
                .put("box", JSONArray(listOf(p.box.left, p.box.top, p.box.right, p.box.bottom))) }))
            .put("readings", JSONArray(l.readings.map { r -> JSONObject().put("text", r.text).put("weight", r.weight).put("count", r.count) })) }))

    private fun parse(j: JSONObject): Article {
        val c = j.getJSONObject("config"); val crop = c.getJSONArray("crop")
        val config = JobConfig(c.getString("uri"), c.getString("title"), Crop(crop.getDouble(0), crop.getDouble(1), crop.getDouble(2), crop.getDouble(3)), c.getLong("startMs"), c.getLong("endMs"), c.getBoolean("formulas"))
        val rows = j.getJSONArray("lines")
        val lines = (0 until rows.length()).map { i ->
            val l = rows.getJSONObject(i); val b = l.getJSONArray("box")
            val readings = l.optJSONArray("readings") ?: JSONArray()
            val parts = l.optJSONArray("formulaParts") ?: JSONArray()
            ArticleLine(l.getInt("id"), l.getString("text"), Box(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)), l.getDouble("confidence"),
                l.getString("source"), l.getLong("timeMs"), BlockKind.valueOf(l.getString("kind")), l.getInt("observations"), l.getBoolean("needsReview"),
                l.optBoolean("clipped"), l.optBoolean("edited"), sourceQuality = l.optDouble("sourceQuality", 0.0), readings = (0 until readings.length()).map { k ->
                    val r = readings.getJSONObject(k); Reading(r.getString("text"), r.getDouble("weight"), r.getInt("count")) }.toMutableList(),
                formulaParts = (0 until parts.length()).map { k -> val p = parts.getJSONObject(k); val box = p.getJSONArray("box")
                    FormulaPart(p.getString("marker"), Box(box.getDouble(0), box.getDouble(1), box.getDouble(2), box.getDouble(3)), p.getString("source")) })
        }
        val metrics = j.optJSONObject("metrics")
        val checkpoint = j.optJSONObject("checkpoint")?.let { c -> ProcessingCheckpoint(c.getString("stage"), c.getLong("timeMs"),
            c.getDouble("offset"), c.getLong("stepMs"), c.getLong("lastOcrTimeMs"), c.getDouble("lastOcrOffset"), c.getDouble("lastQuality")) }
        return Article(j.getString("id"), j.getString("title"), j.getLong("created"), j.getString("status"), config, lines,
            j.optInt("frames"), j.optInt("duplicates"), j.optString("message"),
            metrics?.keys()?.asSequence()?.associateWith { metrics.getLong(it) } ?: emptyMap(), checkpoint)
    }
}
