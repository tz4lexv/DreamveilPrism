package dev.dreamveil.prism.pack;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Structured reflection of shaderc's real SPIR-V, not GLSL name heuristics.
 * Opcode/operand layout follows Khronos SPIRV-Headers unified1 grammar.
 * OpName identifies a symbol only; it never selects rendering behavior.
 */
final class PrismSpirvReflection {
    enum Category { SAMPLED_IMAGE, STORAGE_IMAGE, UBO, SSBO, PUSH_CONSTANT, UNSUPPORTED }
    record Descriptor(int id, String name, Category category, int set, int binding, int imageFormat,
                      int dimension, boolean arrayed, boolean multisampled, List<Integer> arraySizes, String access, String sampledType) {}
    record Interface(int id, String name, int storage, int location, int typeId, String type) {}
    record Module(int executionModel, List<Integer> localSize, List<Descriptor> descriptors,
                  List<Interface> interfaces, Map<Integer,Integer> specializationIds) {}
    private record Type(int opcode, int[] operands) {}

    static Module read(ByteBuffer bytes, String path) throws PrismPackLoadException {
        try { return parse(bytes); }
        catch (RuntimeException failure) {
            throw new PrismPackLoadException("spirv_reflection", "Invalid or unsupported SPIR-V structure: " + failure.getMessage(), path, failure);
        }
    }
    private static Module parse(ByteBuffer bytes) {
        IntBuffer words = words(bytes);
        Map<Integer,Type> types = new HashMap<>();
        Map<Integer,String> names = new HashMap<>();
        Map<Integer,Map<Integer,Integer>> decorations = new HashMap<>();
        Map<Integer,Map<Integer,Set<Integer>>> memberDecorations = new HashMap<>();
        Map<Integer,Integer> constants = new HashMap<>();
        List<int[]> variables = new ArrayList<>();
        List<Integer> local = List.of(); int model = -1, entryCount = 0;
        for (int at = 5; at < words.limit();) {
            int header = words.get(at), count = header >>> 16, op = header & 65535;
            if (count < 1 || at + count > words.limit()) throw new IllegalArgumentException("instruction word count");
            int[] a = new int[count - 1]; for (int i = 0; i < a.length; i++) a[i] = words.get(at + i + 1);
            switch (op) {
                case 5 -> names.put(a[0], string(a, 1));
                case 15 -> { model = a[0]; entryCount++; }
                case 16 -> { if (a[1] == 17) local = List.of(a[2], a[3], a[4]); }
                case 19,20,21,22,23,24,25,26,27,28,29,30,32 -> types.put(a[0], new Type(op,a));
                case 43 -> { if (a.length == 3) constants.put(a[1],a[2]); }
                case 59 -> variables.add(a);
                case 71 -> decorations.computeIfAbsent(a[0], k -> new HashMap<>()).put(a[1], a.length > 2 ? a[2] : 1);
                case 72 -> memberDecorations.computeIfAbsent(a[0], k -> new HashMap<>()).computeIfAbsent(a[1], k -> new HashSet<>()).add(a[2]);
                default -> { /* Non-interface instructions do not define descriptor semantics. */ }
            }
            at += count;
        }
        if (entryCount != 1) throw new IllegalArgumentException("one entry point required");
        List<Descriptor> descriptors = new ArrayList<>(); List<Interface> interfaces = new ArrayList<>();
        for (int[] v : variables) {
            int id = v[1], storage = v[2];
            Type pointer = Objects.requireNonNull(types.get(v[0]));
            if (pointer.opcode != 32) throw new IllegalArgumentException("variable pointer type");
            int typeId = pointer.operands[2];
            var d = decorations.getOrDefault(id, Map.of());
            if (storage == 1 || storage == 3) {
                interfaces.add(new Interface(id, names.getOrDefault(id,""), storage, d.getOrDefault(30,-1),typeId,signature(typeId,types,0)));
                continue;
            }
            if (storage != 0 && storage != 2 && storage != 9 && storage != 12) continue;
            Type type = Objects.requireNonNull(types.get(typeId));
            List<Integer> arrays = new ArrayList<>();
            while (type.opcode == 28 || type.opcode == 29) {
                arrays.add(type.opcode == 29 ? -1 : constants.getOrDefault(type.operands[2], -1));
                type = Objects.requireNonNull(types.get(type.operands[1]));
            }
            Category category = Category.UNSUPPORTED;
            int format = 0, dimension = -1; boolean arrayed = false, ms = false; String sampledType="";
            if (storage == 9) category = Category.PUSH_CONSTANT;
            else if (storage == 12 || (storage == 2 && decorations.getOrDefault(type.operands[0], Map.of()).containsKey(3))) category = Category.SSBO;
            else if (storage == 2 && decorations.getOrDefault(type.operands[0], Map.of()).containsKey(2)) category = Category.UBO;
            else {
                if (type.opcode == 27) { category = Category.SAMPLED_IMAGE; type = Objects.requireNonNull(types.get(type.operands[1])); }
                if (type.opcode == 25) {
                    if (type.operands[6] == 2) category = Category.STORAGE_IMAGE;
                    dimension = type.operands[2]; arrayed = type.operands[4] != 0; ms = type.operands[5] != 0; format = type.operands[7];
                    sampledType=signature(type.operands[1],types,0);
                }
            }
            boolean readOnly=d.containsKey(24), writeOnly=d.containsKey(25);
            if(type.opcode==30 && type.operands.length>1) {
                var members=memberDecorations.getOrDefault(type.operands[0],Map.of());
                boolean allReadOnly=true, allWriteOnly=true;
                for(int member=0;member<type.operands.length-1;member++) {
                    var attributes=members.getOrDefault(member,Set.of());
                    allReadOnly &= attributes.contains(24); allWriteOnly &= attributes.contains(25);
                }
                readOnly |= allReadOnly; writeOnly |= allWriteOnly;
            }
            String access = category == Category.SAMPLED_IMAGE || category == Category.UBO || readOnly ? "read" : writeOnly ? "write" : "read_write";
            descriptors.add(new Descriptor(id,names.getOrDefault(id,""),category,d.getOrDefault(34,-1),d.getOrDefault(33,-1),format,dimension,arrayed,ms,List.copyOf(arrays),access,sampledType));
        }
        descriptors.sort(Comparator.comparing(Descriptor::category).thenComparing(Descriptor::name));
        interfaces.sort(Comparator.comparingInt(Interface::storage).thenComparingInt(Interface::location).thenComparing(Interface::name));
        Map<Integer,Integer> specs = new TreeMap<>();
        decorations.forEach((id,d) -> { if (d.containsKey(1)) specs.put(id,d.get(1)); });
        return new Module(model,List.copyOf(local),List.copyOf(descriptors),List.copyOf(interfaces),Collections.unmodifiableMap(specs));
    }
    static void rebind(ByteBuffer bytes, Map<String,Integer> assignments, String path) throws PrismPackLoadException {
        Module module = read(bytes,path); Map<Integer,Integer> byId = new HashMap<>();
        for (Descriptor d : module.descriptors) {
            Integer binding = assignments.get(d.name);
            if (binding == null || d.binding < 0 || d.set < 0) throw new PrismPackLoadException("spirv_binding", "No verified binding for symbol '" + d.name + "'", path);
            byId.put(d.id,binding);
        }
        IntBuffer words = words(bytes);
        for (int at=5; at<words.limit();) {
            int count=words.get(at)>>>16, op=words.get(at)&65535;
            if (op==71 && count>=4 && byId.containsKey(words.get(at+1))) {
                int decoration=words.get(at+2);
                if (decoration==33) words.put(at+3,byId.get(words.get(at+1)));
                if (decoration==34) words.put(at+3,0);
            }
            at+=count;
        }
    }
    private static String signature(int id,Map<Integer,Type> types,int depth) {
        if(depth>16) return "nested";
        Type type=types.get(id); if(type==null) return "unknown";
        return switch(type.opcode) {
            case 20 -> "bool";
            case 21 -> (type.operands[2]==0?"uint":"int")+type.operands[1];
            case 22 -> "float"+type.operands[1];
            case 23 -> "vec"+type.operands[2]+"<"+signature(type.operands[1],types,depth+1)+">";
            default -> "type-op"+type.opcode;
        };
    }
    private static IntBuffer words(ByteBuffer bytes) {
        var result=bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        if (bytes.remaining()%4!=0 || result.limit()<5 || result.get(0)!=0x07230203) throw new IllegalArgumentException("SPIR-V header");
        return result;
    }
    private static String string(int[] words,int start) {
        var bytes=new java.io.ByteArrayOutputStream();
        for(int i=start;i<words.length;i++) for(int shift=0;shift<32;shift+=8) {
            int b=(words[i]>>>shift)&255; if(b==0) return bytes.toString(StandardCharsets.UTF_8); bytes.write(b);
        }
        throw new IllegalArgumentException("unterminated string");
    }
}
