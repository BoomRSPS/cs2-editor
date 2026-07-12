package dawn.cs2;

import com.displee.cache.CacheLibrary;
import com.displee.io.impl.OutputBuffer;
import dawn.cs2.instructions.*;

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

    /**
     * Copy whole interface groups (index 3 archives, each holding one file per component) from
     * {@code src} into {@code dst}, replacing any existing group in the destination, and persist.
     *
     * Interface definitions are opcode-based and a newer client's decoder is a superset of an older
     * one's, so an older group decodes fine in a newer cache — this is a plain archive copy, no footer
     * transcode like scripts need. NOTE: for groups that already exist in {@code dst}, this OVERWRITES
     * the destination's version (e.g. reverts a stock 239 interface to the source cache's version).
     */
    public static Result portInterfaces(CacheLibrary src, CacheLibrary dst, int[] groups) {
        Result result = new Result();
        for (int group : groups) {
            try {
                var srcArchive = src.index(INTERFACE_INDEX).archive(group);
                if (srcArchive == null) {
                    result.failed.put(group, "not present in source cache index " + INTERFACE_INDEX);
                    continue;
                }
                var files = srcArchive.files();
                // drop any existing destination group first so stale components don't linger
                dst.remove(INTERFACE_INDEX, group);
                int copied = 0;
                for (var f : files) {
                    byte[] fileData = f.getData();
                    if (fileData == null) continue;
                    dst.put(INTERFACE_INDEX, group, f.getId(), fileData, (int[]) null);
                    copied++;
                }
                if (copied == 0) {
                    result.failed.put(group, "source group had no component data");
                    continue;
                }
                result.ported.add(group);
            } catch (Exception e) {
                result.failed.put(group, String.valueOf(e.getMessage()));
            }
        }
        if (!result.ported.isEmpty()) {
            var index = dst.index(INTERFACE_INDEX);
            if (index == null || !index.update()) {
                throw new IllegalStateException("Failed to persist destination cache index " + INTERFACE_INDEX);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // Cross-revision script adaptation: some clientscript opcodes changed their argument count between
    // the older cache's revision and 239. A byte-perfect script copy therefore feeds the 239 client the
    // wrong number of stack values and crashes (stack underflow). We fix this by rewriting the
    // instruction stream: for each affected opcode we insert N `push_int 0` immediately before it so the
    // stack matches what the 239 client pops. The extra arg(s) are the new trailing parameter(s) (the
    // 239 native scripts pass 0 there), verified against the 239 client's ScriptRunner.
    //
    // Key: editor MASTER opcode as returned by CS2Reader (after unscrambling). Value: number of
    // `push_int 0` to insert before each occurrence.
    //   150 = cc_create/createChild: 3 args in <=~236, 4 args in 239 (added a trailing flag).
    // osrs.txt maps master 150 <-> cache 100, so createChild (written as cache opcode 100, hence the
    // client's "command 100" crash) reads back here as master 150. Keying on cache 100 never matches -
    // the reader has already unscrambled it to 150.
    // ---------------------------------------------------------------------------------------------
    public static final Map<Integer, Integer> REV239_INT_ARG_INSERTS = Map.of(150, 1);

    /**
     * Read a script from {@code src} (in {@code srcLongs} footer format), rewrite its instruction stream
     * to match 239 opcode conventions (see {@link #REV239_INT_ARG_INSERTS}), and re-serialise it in the
     * {@code dstLongs} footer format. Returns the ready-to-write bytes, or null if the script is absent.
     */
    public static byte[] adaptScript(byte[] data, int id, Map<Integer, Integer> unscramble, Map<Integer, Integer> scramble,
                                     boolean disableSwitches, boolean srcLongs, boolean dstLongs) throws Exception {
        CS2 script = CS2Reader.readCS2ScriptNewFormat(data, id, unscramble, disableSwitches, !srcLongs);
        List<AbstractInstruction> out = new ArrayList<>();
        int inserted = 0;
        for (AbstractInstruction ins : script.getInstructions()) {
            if (ins == null) continue;
            Integer n = REV239_INT_ARG_INSERTS.get(ins.getOpcode());
            if (n != null && (ins instanceof BooleanInstruction || ins instanceof IntInstruction)) {
                for (int k = 0; k < n; k++) out.add(new IntInstruction(Opcodes.PUSH_INT, 0));
                inserted += n;
            }
            out.add(ins);
        }
        // No arg-count-changed opcode in this script: leave the bytes byte-for-byte untouched rather
        // than round-tripping them through reserialize (which would risk altering scripts needlessly).
        if (inserted == 0) return data;
        return reserialize(out, script, scramble, dstLongs, !disableSwitches);
    }

    /** Serialise an instruction list (labels for jump/switch targets) + a script's local/arg counts to bytes. */
    private static byte[] reserialize(List<AbstractInstruction> instrs, CS2 script, Map<Integer, Integer> scramble,
                                      boolean supportLongs, boolean supportSwitch) {
        // assign addresses: labels take the address of the following real instruction, reals are sequential
        int addr = 0;
        List<AbstractInstruction> code = new ArrayList<>(instrs.size());
        for (AbstractInstruction instr : instrs) {
            if (instr instanceof Label) {
                instr.setAddress(addr);
                ((Label) instr).setLabelID(addr);
            } else {
                instr.setAddress(addr++);
                code.add(instr);
            }
        }
        OutputBuffer output = new OutputBuffer(Math.max(64, code.size() * 4 + 64));
        output.writeByte(0); // empty name terminator
        int switchCount = 0;
        for (AbstractInstruction instr : code) {
            int opcode = instr.getOpcode();
            Integer n = scramble.get(opcode);
            if (n == null) n = opcode; // OSRS identity fallback
            output.writeShort(n);
            if (instr instanceof SwitchInstruction) {
                output.writeInt(switchCount++);
            } else if (instr instanceof LongInstruction) {
                output.writeLong(((LongInstruction) instr).getConstant());
            } else if (instr instanceof StringInstruction) {
                output.writeString(((StringInstruction) instr).getConstant());
            } else if (instr instanceof JumpInstruction) {
                output.writeInt(((JumpInstruction) instr).getTarget().getAddress() - instr.getAddress() - 1);
            } else if (instr instanceof IntInstruction) {
                // The reader models RETURN/POP_INT/POP_STRING/POP_LONG as IntInstructions but their operand
                // is a single byte on disk (not a 4-byte int); match that so the stream stays aligned.
                int op = instr.getOpcode();
                if (op == Opcodes.RETURN || op == Opcodes.POP_INT || op == Opcodes.POP_STRING || op == Opcodes.POP_LONG) {
                    output.writeByte(((IntInstruction) instr).getConstant());
                } else {
                    output.writeInt(((IntInstruction) instr).getConstant());
                }
            } else if (instr instanceof BooleanInstruction) {
                output.writeByte(((BooleanInstruction) instr).getConstant() ? 1 : 0);
            } else {
                throw new IllegalStateException("Cannot serialise instruction " + instr.getClass());
            }
        }
        output.writeInt(code.size());
        output.writeShort(script.getIntLocalsSize());
        output.writeShort(script.getStringLocalsSize());
        if (supportLongs) output.writeShort(script.getLongLocalsSize());
        output.writeShort(script.getIntArgumentsCount());
        output.writeShort(script.getStringArgumentsCount());
        if (supportLongs) output.writeShort(script.getLongArgumentsCount());
        if (supportSwitch) {
            long markSwitch = output.getOffset();
            output.writeByte(switchCount);
            for (AbstractInstruction instr : code) {
                if (instr instanceof SwitchInstruction) {
                    SwitchInstruction sw = (SwitchInstruction) instr;
                    output.writeShort(sw.cases.size());
                    for (int c = 0; c < sw.cases.size(); c++) {
                        output.writeInt(sw.cases.get(c));
                        output.writeInt(sw.targets.get(c).getAddress() - instr.getAddress() - 1);
                    }
                }
            }
            output.writeShort((short) (output.getOffset() - markSwitch));
        }
        return output.array();
    }

    /**
     * Port scripts from {@code src} into {@code dst}, adapting each to 239 opcode conventions. Footer
     * formats are auto-detected. Use this (instead of {@link #port}) when moving scripts UP across a
     * revision where opcode signatures changed.
     */
    public static Result portScriptsAdapting(CacheLibrary src, CacheLibrary dst, int[] ids) {
        Map<Integer, Integer> unscramble = scriptConfiguration().getUnscrambled();
        Map<Integer, Integer> scramble = scriptConfiguration().getScrambled();
        boolean disableSwitches = scriptConfiguration().getDisableSwitches();
        boolean srcLongs = !ScriptConfiguration.detectDisableLongs(src, unscramble, disableSwitches);
        boolean dstLongs = !ScriptConfiguration.detectDisableLongs(dst, unscramble, disableSwitches);
        Result result = new Result();
        for (int id : ids) {
            try {
                byte[] data = src.data(SCRIPTS_INDEX, id);
                if (data == null) {
                    result.failed.put(id, "not present in source cache");
                    continue;
                }
                byte[] adapted = adaptScript(data, id, unscramble, scramble, disableSwitches, srcLongs, dstLongs);
                dst.put(SCRIPTS_INDEX, id, adapted);
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
    private static final int INTERFACE_INDEX = 3;

    private static ScriptConfiguration scriptConfiguration() {
        return CS2ConstantsKt.getScriptConfiguration();
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }
}
