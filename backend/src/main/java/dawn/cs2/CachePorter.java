package dawn.cs2;

import com.displee.cache.CacheLibrary;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Copies CS2 scripts from one cache into another, transcoding the script footer between the two OSRS
 * trailer formats when the caches differ.
 *
 * OSRS rev 237+ added a long-variable section to the script footer: the 12-byte footer
 * {@code codeSize, intLocals, strLocals, intArgs, strArgs} became the 16-byte footer
 * {@code codeSize, intLocals, strLocals, longLocals, intArgs, strArgs, longArgs}. The instruction
 * stream itself is byte-identical between revisions for shared opcodes, so porting a script only
 * requires rewriting the footer — no decompile/recompile — which works for every script, even ones
 * the decompiler can't handle.
 */
public class CachePorter {

    public static class Result {
        public final List<Integer> ported = new ArrayList<>();
        public final Map<Integer, String> failed = new java.util.LinkedHashMap<>();

        @Override
        public String toString() {
            return "ported " + ported.size() + ", failed " + failed.size()
                    + (failed.isEmpty() ? "" : " " + failed);
        }
    }

    /**
     * Rewrite raw (decompressed) script bytes so their footer matches the destination trailer format.
     *
     * @param data         decompressed script bytes as returned by {@code CacheLibrary.data(12, id)}
     * @param srcHasLongs  true if {@code data} uses the 16-byte (rev 237+) footer
     * @param dstHasLongs  true if the destination cache expects the 16-byte footer
     * @param hasSwitches  true if the cache uses switch tables (always true for modern OSRS)
     * @return script bytes in the destination footer format (a copy; {@code data} is not modified)
     */
    public static byte[] transcode(byte[] data, boolean srcHasLongs, boolean dstHasLongs, boolean hasSwitches) {
        if (srcHasLongs == dstHasLongs) {
            return data.clone();
        }
        int len = data.length;
        int switchLen = hasSwitches ? u16(data, len - 2) : 0;
        int srcTrailerBytes = srcHasLongs ? 16 : 12;
        int switchTail = hasSwitches ? 2 : 0;
        int trailerStart = len - switchLen - srcTrailerBytes - switchTail;
        if (trailerStart < 0 || trailerStart + srcTrailerBytes > len) {
            throw new IllegalArgumentException("Cannot locate script footer (trailerStart=" + trailerStart
                    + ", len=" + len + "); source format may be wrong.");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(len + 4);
        // name + instruction stream, unchanged
        out.write(data, 0, trailerStart);
        // codeSize (4) + intLocals (2) + strLocals (2) — first 8 footer bytes, common to both formats
        out.write(data, trailerStart, 8);

        if (dstHasLongs) {
            // 12 -> 16: insert a zero long-local count and a zero long-arg count
            out.write(0);
            out.write(0);                                   // longLocals = 0
            out.write(data, trailerStart + 8, 4);           // intArgs + strArgs
            out.write(0);
            out.write(0);                                   // longArgs = 0
            out.write(data, trailerStart + 12, len - (trailerStart + 12)); // switch region + switch size short
        } else {
            // 16 -> 12: drop the long counts (only possible if the script uses no long variables)
            int longLocals = u16(data, trailerStart + 8);
            int longArgs = u16(data, trailerStart + 14);
            if (longLocals != 0 || longArgs != 0) {
                throw new IllegalArgumentException("Script uses long variables (longLocals=" + longLocals
                        + ", longArgs=" + longArgs + ") and cannot be ported to a pre-237 cache.");
            }
            out.write(data, trailerStart + 10, 4);          // intArgs + strArgs (skip longLocals)
            out.write(data, trailerStart + 16, len - (trailerStart + 16)); // switch region + switch size short
        }
        return out.toByteArray();
    }

    /**
     * Port the given script ids from {@code src} into {@code dst}, transcoding footers as needed, and
     * persist the destination index. Footer formats are auto-detected per cache.
     */
    public static Result port(CacheLibrary src, CacheLibrary dst, int[] ids) {
        Map<Integer, Integer> unscramble = scriptConfiguration().getUnscrambled();
        boolean disableSwitches = scriptConfiguration().getDisableSwitches();
        boolean srcLongs = !ScriptConfiguration.detectDisableLongs(src, unscramble, disableSwitches);
        boolean dstLongs = !ScriptConfiguration.detectDisableLongs(dst, unscramble, disableSwitches);
        return port(src, dst, ids, srcLongs, dstLongs, !disableSwitches);
    }

    /** Port with explicit footer formats (skips auto-detection). */
    public static Result port(CacheLibrary src, CacheLibrary dst, int[] ids,
                              boolean srcLongs, boolean dstLongs, boolean hasSwitches) {
        Result result = new Result();
        for (int id : ids) {
            try {
                byte[] data = src.data(SCRIPTS_INDEX, id);
                if (data == null) {
                    result.failed.put(id, "not present in source cache");
                    continue;
                }
                byte[] converted = transcode(data, srcLongs, dstLongs, hasSwitches);
                dst.put(SCRIPTS_INDEX, id, converted);
                result.ported.add(id);
            } catch (Exception e) {
                result.failed.put(id, String.valueOf(e.getMessage()));
            }
        }
        if (!result.ported.isEmpty()) {
            var index = dst.index(SCRIPTS_INDEX);
            if (index == null || !index.update()) {
                throw new IllegalStateException("Failed to persist destination cache index " + SCRIPTS_INDEX);
            }
        }
        return result;
    }

    private static final int SCRIPTS_INDEX = CS2ConstantsKt.SCRIPTS_INDEX;

    private static ScriptConfiguration scriptConfiguration() {
        return CS2ConstantsKt.getScriptConfiguration();
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }
}
