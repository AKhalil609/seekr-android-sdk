package tv.seekr.previews.android.internal

import java.io.File
import java.util.Properties
import tv.seekr.previews.core.SeekrContent

/** What a cached file is, so a later start can resume its upload without the player: the content envelope and the exact duration. */
internal data class FileMeta(val content: SeekrContent, val durationMs: Long)

internal class CachedTile(val cue: Int, val ptsMs: Long, val size: Int, val file: File) {
    fun read(): ByteArray = file.readBytes()
}

/**
 * The on-disk capture buffer: one directory per `fp` under [root], holding `t_<cue>_<ptsMs>.jpg` tiles and a `meta.properties`.
 *
 * Bounded (PACKAGE-SPEC §7.3.6): at most [maxBytes] over all files (least recently used file evicted first), entries not touched for
 * [maxAgeMs] expire. The last-access time is stored in the meta (written through [clock]) rather than read from file mtimes, so
 * the policy is deterministic and survives file copies. Everything survives a restart because it is just files; the
 * directory is [android.content.Context.getCacheDir]-based so the OS may reclaim it, which only costs unsent tiles.
 *
 * Writes go to a temp name and are renamed, so a crash never leaves a truncated tile that an upload would then choke on.
 * Methods are synchronized: they do small file operations, called from the capture loop, the uploader and `resumePending`.
 */
internal class TileCache(
    private val root: File,
    private val maxBytes: Long,
    private val maxAgeMs: Long,
    private val clock: () -> Long,
) {
    private val fpPattern = Regex("[0-9a-f]{64}")

    fun isValidFp(fp: String): Boolean = fpPattern.matches(fp)

    private fun dir(fp: String): File {
        require(isValidFp(fp)) { "fp must be 64 lowercase hex" }
        return File(root, fp)
    }

    /** Stores a captured tile and enforces the size cap, never evicting the file being written to. */
    @Synchronized
    fun put(fp: String, meta: FileMeta, cue: Int, ptsMs: Long, jpeg: ByteArray) {
        val d = dir(fp)
        if (!d.isDirectory && !d.mkdirs()) return
        writeMeta(d, meta, stopped = isStopped(fp))
        val target = File(d, "t_${cue}_$ptsMs.jpg")
        // A cue is captured once; a second tile for it (re-requested overlap) replaces the first.
        d.listFiles()?.filter { parseTile(it)?.cue == cue }?.forEach { it.delete() }
        val tmp = File(d, "tmp_${cue}")
        tmp.writeBytes(jpeg)
        if (!tmp.renameTo(target)) tmp.delete()
        enforceCap(protect = fp)
    }

    @Synchronized
    fun tiles(fp: String): List<CachedTile> {
        val d = dir(fp)
        return d.listFiles()?.mapNotNull { parseTile(it) }?.sortedBy { it.cue } ?: emptyList()
    }

    @Synchronized
    fun cues(fp: String): Set<Int> = tiles(fp).mapTo(HashSet()) { it.cue }

    @Synchronized
    fun remove(fp: String, cues: Collection<Int>) {
        val set = cues.toSet()
        tiles(fp).filter { it.cue in set }.forEach { it.file.delete() }
        touch(fp)
    }

    /** Drops everything of [fp] (job closed, have_exact, ...). */
    @Synchronized
    fun drop(fp: String) {
        dir(fp).deleteRecursively()
    }

    /** `StopFile`: no more contributions for [fp] until its entry expires. Keeps only the meta, so a restart does not retry. */
    @Synchronized
    fun markStopped(fp: String, meta: FileMeta) {
        val d = dir(fp)
        d.listFiles()?.filter { parseTile(it) != null }?.forEach { it.delete() }
        d.mkdirs()
        writeMeta(d, meta, stopped = true)
    }

    @Synchronized
    fun isStopped(fp: String): Boolean = readMeta(dir(fp))?.stopped == true

    @Synchronized
    fun meta(fp: String): FileMeta? = readMeta(dir(fp))?.meta

    @Synchronized
    fun touch(fp: String) {
        val d = dir(fp)
        val m = readMeta(d) ?: return
        writeMeta(d, m.meta, m.stopped)
    }

    /** Files that have at least one tile and may be uploaded, oldest access first (resume order). */
    @Synchronized
    fun pendingFiles(): List<String> =
        entries().filter { !it.stopped && it.tiles > 0 }.sortedBy { it.lastAccessMs }.map { it.fp }

    @Synchronized
    fun totalBytes(): Long = entries().sumOf { it.bytes }

    /** Removes expired entries and trims to the cap. Call at start. */
    @Synchronized
    fun purge() {
        val now = clock()
        entries().filter { now - it.lastAccessMs > maxAgeMs }.forEach { File(root, it.fp).deleteRecursively() }
        enforceCap(protect = null)
    }

    private class Entry(val fp: String, val lastAccessMs: Long, val bytes: Long, val tiles: Int, val stopped: Boolean)

    private fun entries(): List<Entry> {
        val dirs = root.listFiles()?.filter { it.isDirectory && isValidFp(it.name) } ?: return emptyList()
        return dirs.map { d ->
            val files = d.listFiles() ?: emptyArray()
            val m = readMeta(d)
            Entry(
                fp = d.name,
                // A directory without a readable meta is garbage from a crash: treat as oldest so it goes first.
                lastAccessMs = m?.lastAccessMs ?: 0L,
                bytes = files.sumOf { it.length() },
                tiles = files.count { parseTile(it) != null },
                stopped = m?.stopped == true,
            )
        }
    }

    private fun enforceCap(protect: String?) {
        var all = entries()
        var total = all.sumOf { it.bytes }
        if (total <= maxBytes) return
        for (e in all.filter { it.fp != protect }.sortedBy { it.lastAccessMs }) {
            if (total <= maxBytes) return
            File(root, e.fp).deleteRecursively()
            total -= e.bytes
        }
        if (total <= maxBytes || protect == null) return
        // Only the file being captured is left and it alone is over the cap: drop its oldest cues (the viewer is further on by now).
        for (t in tiles(protect)) {
            if (total <= maxBytes) return
            val size = t.size.toLong()
            if (t.file.delete()) total -= size
        }
    }

    private fun parseTile(f: File): CachedTile? {
        val n = f.name
        if (!n.startsWith("t_") || !n.endsWith(".jpg")) return null
        val parts = n.removePrefix("t_").removeSuffix(".jpg").split('_')
        if (parts.size != 2) return null
        val cue = parts[0].toIntOrNull() ?: return null
        val pts = parts[1].toLongOrNull() ?: return null
        return CachedTile(cue, pts, f.length().toInt(), f)
    }

    private class StoredMeta(val meta: FileMeta, val lastAccessMs: Long, val stopped: Boolean)

    private fun writeMeta(d: File, meta: FileMeta, stopped: Boolean) {
        val p = Properties()
        p["duration_ms"] = meta.durationMs.toString()
        p["last_access_ms"] = clock().toString()
        p["stopped"] = stopped.toString()
        when (val c = meta.content) {
            is SeekrContent.Movie -> {
                p["kind"] = "movie"
                c.tmdbId?.let { p["tmdb_id"] = it.toString() }
                c.imdbId?.let { p["imdb_id"] = it }
            }
            is SeekrContent.Episode -> {
                p["kind"] = "episode"
                c.showTmdbId?.let { p["show_tmdb_id"] = it.toString() }
                c.showImdbId?.let { p["show_imdb_id"] = it }
                p["season"] = c.season.toString()
                p["episode"] = c.episode.toString()
            }
            is SeekrContent.Anime -> {
                p["kind"] = "anime"
                p["kitsu_id"] = c.kitsuId.toString()
                c.episode?.let { p["episode"] = it.toString() }
            }
        }
        val tmp = File(d, "meta.tmp")
        tmp.outputStream().use { p.store(it, null) }
        val target = File(d, META)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    private fun readMeta(d: File): StoredMeta? {
        val f = File(d, META)
        if (!f.isFile) return null
        return try {
            val p = Properties().also { props -> f.inputStream().use { props.load(it) } }
            val content = when (p.getProperty("kind")) {
                "movie" -> SeekrContent.Movie(p.getProperty("tmdb_id")?.toInt(), p.getProperty("imdb_id"))
                "episode" -> SeekrContent.Episode(
                    p.getProperty("show_tmdb_id")?.toInt(),
                    p.getProperty("show_imdb_id"),
                    p.getProperty("season").toInt(),
                    p.getProperty("episode").toInt(),
                )
                "anime" -> SeekrContent.Anime(p.getProperty("kitsu_id").toInt(), p.getProperty("episode")?.toInt())
                else -> return null
            }
            StoredMeta(
                FileMeta(content, p.getProperty("duration_ms").toLong()),
                p.getProperty("last_access_ms").toLong(),
                p.getProperty("stopped") == "true",
            )
        } catch (e: Exception) { // NumberFormatException, NPE on a missing key, IllegalArgumentException from the content checks
            null
        }
    }

    private companion object {
        const val META = "meta.properties"
    }
}
