package dawn.cs2

import com.displee.cache.CacheLibrary
import dawn.cs2.unscramble.UnscrambleUtils
import dawn.cs2.util.FunctionDatabase
import java.io.StringWriter

data class ScriptConfiguration(val opcodeDatabase: String = "/cs2/opcode/database/osrs.ini", val disableSwitches: Boolean = false, val disableLongs: Boolean = false) {

    val scrambled = hashMapOf<Int, Int>()
    val unscrambled = hashMapOf<Int, Int>()

    init {
        UnscrambleUtils.read(scrambled, unscrambled)
    }

    override fun toString(): String {
        return "ScriptConfiguration[database=$opcodeDatabase, switches=${!disableSwitches}, longs=${!disableLongs}]"
    }

    companion object {
        /**
         * The CS2 script footer isn't self-describing: OSRS rev 237+ added a long-variable section to the
         * trailer (16-byte footer), while older caches (<=236) use the 12-byte footer. Reading with the
         * wrong assumption misaligns the whole script (huge codeSize, buffer overruns), so probe a sample
         * of scripts both ways and pick whichever reads cleanly.
         *
         * @return the disableLongs flag to use: true = old 12-byte footer (longs off), false = 237+ footer.
         */
        @JvmStatic
        fun detectDisableLongs(cache: CacheLibrary, unscramble: Map<Int, Int>, disableSwitches: Boolean): Boolean {
            val ids = cache.index(SCRIPTS_INDEX)?.archiveIds() ?: return false
            var withLongs = 0    // clean reads treating the footer as 16-byte (longs enabled)
            var withoutLongs = 0 // clean reads treating the footer as 12-byte (longs disabled)
            var sampled = 0
            for (id in ids) {
                if (sampled >= 80) break
                val data = try {
                    cache.data(SCRIPTS_INDEX, id)
                } catch (e: Exception) {
                    null
                } ?: continue
                if (data.size < 16) continue
                sampled++
                if (readsClean(data, id, unscramble, disableSwitches, false)) withLongs++
                if (readsClean(data, id, unscramble, disableSwitches, true)) withoutLongs++
            }
            // In practice one mode reads virtually all scripts and the other throws on most; a clear
            // winner. Keep longs enabled on an exact tie (matches the modern-cache default).
            return withoutLongs > withLongs
        }

        private fun readsClean(data: ByteArray, id: Int, unscramble: Map<Int, Int>, disableSwitches: Boolean, disableLongs: Boolean): Boolean {
            return try {
                CS2Reader.readCS2ScriptNewFormat(data, id, unscramble, disableSwitches, disableLongs) != null
            } catch (t: Throwable) {
                false
            }
        }
    }

    fun generateScriptsDatabase(cacheLibrary: CacheLibrary, loop: Int = 6): FunctionDatabase {
        val opcodesDatabase = FunctionDatabase(javaClass.getResourceAsStream(opcodeDatabase), false, scrambled)
        val scriptsDatabase = FunctionDatabase()
        val ids = cacheLibrary.index(SCRIPTS_INDEX)!!.archiveIds()
        for (l in 0 until loop) {
            for (id in ids) {
                val data = cacheLibrary.data(SCRIPTS_INDEX, id)
                try {
                    val script = CS2Reader.readCS2ScriptNewFormat(data, id, unscrambled, disableSwitches, disableLongs)
                    val decompiler = CS2Decompiler(script, opcodesDatabase, scriptsDatabase)
                    try {
                        decompiler.decompile()
                    } catch (ex: Throwable) {
                    }
                    val function = decompiler.function
                    if (function.returnType == CS2Type.UNKNOWN) {
                        continue
                    }
                    val info = scriptsDatabase.getInfo(id)
                    info.name = function.name
                    if (info.returnType === CS2Type.UNKNOWN) {
                        info.returnType = function.returnType
                    }
                    for (a in function.argumentLocals.indices) {
                        info.argumentTypes[a] = function.argumentLocals[a].type
                        info.argumentNames[a] = function.argumentLocals[a].name
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // unknown scrambling etc
                }
            }
        }
        var successCount = 0
        val writer = StringWriter()
        for (id in ids) {
            val info = scriptsDatabase.getInfo(id)
            if (info?.getReturnType() == null /* || info.getReturnType() == CS2Type.UNKNOWN*/) {
                continue
            }
            if (info.returnType != CS2Type.UNKNOWN) {
                successCount++
            }
            if (info.getReturnType().isStructure) {
                writer.write("$id ${info.getName()} {${info.getReturnType().toString().replace(" ".toRegex(), "")}}")
            } else {
                writer.write("$id ${info.getName()} ${info.getReturnType()}")
            }
            for (a in info.getArgumentTypes().indices) {
                writer.write(" ${info.getArgumentTypes()[a]} ${info.getArgumentNames()[a]}")
            }
            writer.write("\r\n")
        }
        println("Generated $successCount/${ids.size} script signatures.")
        val signatures = writer.toString()
        return FunctionDatabase(signatures, true, null)
    }
}
