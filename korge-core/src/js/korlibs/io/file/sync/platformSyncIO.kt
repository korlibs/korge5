package korlibs.io.file.sync

import korlibs.io.jsObject
import korlibs.io.lang.unsupported
import korlibs.js.Deno
import korlibs.js.DenoFileInfo
import korlibs.js.toArray
import korlibs.memory.Platform
import org.khronos.webgl.Uint8Array

object DenoSyncIO : SyncIO {
    override fun realpath(path: String): String = Deno.realPathSync(path)
    override fun readlink(path: String): String? = Deno.readLinkSync(path)
    override fun writelink(path: String, link: String?) { Deno.linkSync(path, link) }
    override fun stat(path: String): SyncIOStat? = runCatching {
        //try {
            //println("STAT: '$path'")
            Deno.statSync(path).toSyncIOStat(path)
        //} catch (e: Throwable) {
        //    e.printStackTrace()
        //    throw e
        //}
    }.getOrNull()
    override fun mkdir(path: String): Boolean = runCatching { Deno.mkdirSync(path) }.isSuccess
    override fun rmdir(path: String): Boolean = runCatching { Deno.removeSync(path) }.isSuccess
    override fun delete(path: String): Boolean = runCatching { Deno.removeSync(path) }.isSuccess
    override fun list(path: String): List<String> = Deno.readDirSync(path).toArray().map { it.name }
    override fun readAllBytes(path: String): ByteArray = Deno.readFileSync(path)
    override fun writeAllBytes(path: String, data: ByteArray) = Deno.writeFileSync(path, data)

    fun DenoFileInfo.toSyncIOStat(path: String): SyncIOStat = SyncIOStat(path, this.isDirectory, this.size.toLong())

    override fun open(path: String, mode: String): SyncIOFD {
        return DenoSyncIOFD(path, mode)
    }
}

class DenoSyncIOFD(val path: String, val mode: String) : SyncIOFD {
    val file = Deno.openSync(path, jsObject("read" to mode.contains("r"), "write" to (mode.contains("+") || mode.contains("w"))))

    override var length: Long
        get() {
            val pos = position
            val total = file.seekSync(0.0, Deno.SeekMode.End).toLong()
            position = pos
            return total
        }
        set(value) { file.truncateSync(value.toDouble()) }
    override var position: Long
        get() = file.seekSync(0.0, Deno.SeekMode.Current).toLong()
        set(value) { file.seekSync(value.toDouble(), Deno.SeekMode.Start) }

    override fun write(data: ByteArray, offset: Int, size: Int): Int =
        file.writeSync((data.unsafeCast<Uint8Array>()).subarray(offset, size)).toInt()

    override fun read(data: ByteArray, offset: Int, size: Int): Int =
        file.readSync((data.unsafeCast<Uint8Array>()).subarray(offset, size))?.toInt() ?: error("Couldn't read")

    override fun close() {
        file.close()
    }
}

actual val platformSyncIO: SyncIO by lazy {
    when {
        Platform.isJsDenoJs -> DenoSyncIO
        //Platform.isJsNodeJs -> NodeSyncIO
        else -> unsupported("Not supported SyncIO on browser")
    }
}
