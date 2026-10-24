package dev.chatocr.app

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import dev.chatocr.core.*
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SessionSummary(val id: String, val title: String, val platform: String, val updatedAt: Long, val count: Int, val pending: Int, val frames: Int, val video: Boolean = false, val incomplete: Boolean = false)

class SessionStore(private val context: Context) {
    // Opt-in diagnostics for the dedicated development emulator; absent on normal installs.
    private val recordObservations = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        File(context.filesDir, "record-ocr-observations").isFile
    private val checkpointJson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
    private val root = File(context.filesDir, "sessions").apply { mkdirs() }
    fun directory(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(root, id).apply { mkdirs() }
    }
    fun load(id: String): Session = ChatExport.fromJson(AtomicFile(File(directory(id), "session.json")).openRead().bufferedReader().use { it.readText() })
    fun save(session: Session) {
        // Checkpoints do not need sorting, copied messages or pretty printing on every frame.
        // Human-readable export still computes chronological time context in ChatExport.
        writeAtomic(File(directory(session.id), "session.json"), checkpointJson.toJson(session))
        val summary = SessionSummary(session.id, session.config.title, session.config.platform, session.updatedAt, session.count(), session.pending(), session.frames.size, session.video != null, session.video?.complete == false)
        writeAtomic(File(directory(session.id), "summary.json"), ChatExport.gson.toJson(summary))
    }
    fun list(): List<SessionSummary> = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
        runCatching {
            ChatExport.gson.fromJson(AtomicFile(File(dir, "summary.json")).openRead().bufferedReader().use { it.readText() }, SessionSummary::class.java)
        }.getOrElse {
            runCatching { val s = load(dir.name); SessionSummary(s.id, s.config.title, s.config.platform, s.updatedAt, s.count(), s.pending(), s.frames.size, s.video != null, s.video?.complete == false) }.getOrNull()
        }
    }?.sortedByDescending { it.updatedAt } ?: emptyList()
    fun frameFile(id: String, frameId: String): File {
        require(frameId.matches(Regex("[a-zA-Z0-9-]+")))
        return File(File(directory(id), "frames").apply { mkdirs() }, "$frameId.jpg")
    }
    fun saveFrame(id: String, frameId: String, bitmap: Bitmap) {
        val atomic = AtomicFile(frameFile(id, frameId))
        val output = atomic.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "无法保存画面" }
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun saveObservation(id: String, frameId: String, height: Float, shift: Float?, candidates: List<Candidate>) {
        if (!recordObservations) return
        require(frameId.matches(Regex("[a-zA-Z0-9-]+")))
        val dir = File(directory(id), "observations").apply { mkdirs() }
        writeAtomic(File(dir, "$frameId.json"), checkpointJson.toJson(mapOf("frameId" to frameId, "height" to height, "shift" to shift, "candidates" to candidates)))
    }
    fun mediaFile(id: String, relative: String): File {
        require(relative.matches(Regex("media/[a-zA-Z0-9-]+\\.jpg"))) { "无效图片路径" }
        return File(directory(id), relative)
    }
    fun saveMedia(session: Session, frameId: String, bitmap: Bitmap) {
        for (message in session.messages) {
            if (message.kind != Kind.MEDIA) continue
            val evidence = message.evidence.firstOrNull { it.frameId == frameId } ?: continue
            val box = evidence.box
            val old = message.media
            if (old != null && old.box.width * old.box.height >= box.width * box.height * .98f) continue
            val scale = bitmap.width / 1000f
            val left = (box.left * scale).toInt().coerceIn(0, bitmap.width - 1)
            val top = (box.top * scale).toInt().coerceIn(0, bitmap.height - 1)
            val right = (box.right * scale).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (box.bottom * scale).toInt().coerceIn(top + 1, bitmap.height)
            if (right - left < 16 || bottom - top < 16) continue
            val relative = "media/${java.util.UUID.randomUUID()}.jpg"
            val target = mediaFile(session.id, relative); target.parentFile?.mkdirs()
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val atomic = AtomicFile(target); val output = atomic.startWrite()
            try {
                check(crop.compress(Bitmap.CompressFormat.JPEG, 94, output)) { "无法保存图片" }
                atomic.finishWrite(output)
                message.media = MediaCrop(relative, frameId, box)
            } catch (e: Exception) { atomic.failWrite(output); throw e }
            finally { if (crop !== bitmap) crop.recycle() }
        }
    }
    fun export(session: Session, format: String, output: OutputStream) {
        requireIdle(session.id)
        when (format) {
            "zip" -> ZipOutputStream(output.buffered()).use { zip ->
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                entry("聊天纪念册.html", ChatExport.html(session, true).toByteArray())
                entry("聊天记录.json", ChatExport.json(session).toByteArray())
                entry("聊天记录.txt", ChatExport.text(session).toByteArray())
                entry("阅读说明.txt", "先完整解压，再打开聊天纪念册.html。包内包含聊天文字、结构化记录和截图。时间以聊天画面显示为准。图片与表情为静态截图，录屏原文件需另行保存。".toByteArray())
                session.frames.forEach { frame ->
                    val file = frameFile(session.id, frame.id)
                    check(file.isFile) { "来源画面缺失，请重新采集后导出" }
                    zip.putNextEntry(ZipEntry("frames/${frame.id}.jpg")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                session.messages.filter { it.kind == Kind.MEDIA }.mapNotNull { it.media?.file }.distinct().forEach { relative ->
                    val file = mediaFile(session.id, relative)
                    check(file.isFile) { "图片缺失，请检查原图后重试" }
                    zip.putNextEntry(ZipEntry(relative)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            "html" -> output.bufferedWriter().use { writer ->
                val media = session.messages.filter { it.kind == Kind.MEDIA }.mapNotNull { it.media?.file }.distinct()
                check(media.sumOf { mediaFile(session.id, it).length() } <= 12L * 1024 * 1024) { "图片较多，请导出 ZIP 阅读包" }
                writer.write(ChatExport.html(session, mediaSource = { message -> message.media?.let {
                    "data:image/jpeg;base64," + android.util.Base64.encodeToString(mediaFile(session.id, it.file).readBytes(), android.util.Base64.NO_WRAP)
                } }))
            }
            "json" -> output.bufferedWriter().use { it.write(ChatExport.json(session)) }
            "txt" -> output.bufferedWriter().use { it.write(ChatExport.text(session)) }
            else -> error("不支持的导出格式")
        }
    }
    fun requireIdle(id: String) {
        check(CaptureRuntime.state.value.sessionId != id || !CaptureRuntime.state.value.active) { "请先结束采集" }
        check(ChatVideoRuntime.state.value.sessionId != id || !ChatVideoRuntime.state.value.running) { "请先暂停录屏识别" }
    }
    fun delete(id: String) {
        requireIdle(id)
        val target = directory(id)
        check(target.canonicalFile.parentFile == root.canonicalFile)
        check(target.deleteRecursively()) { "删除失败" }
    }
    private fun writeAtomic(file: File, text: String) {
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
}
