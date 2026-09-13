package top.niunaijun.blackbox.utils;

import java.io.File;
import java.util.*;

/** Manifest-based split selection. Resource configurations and feature modules are not discarded. */
public final class SplitApkPlanner {
    private static final List<String> ABIS = Arrays.asList("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "riscv64");
    public static final class Part {
        public final File file;
        public final String packageName, splitName;
        public final long version;
        public final Set<String> nativeAbis;
        public Part(File file, String packageName, String splitName, long version, Set<String> nativeAbis) {
            this.file = file;
            this.packageName = packageName;
            this.splitName = splitName == null || splitName.isEmpty() ? null : splitName;
            this.version = version;
            this.nativeAbis = new HashSet<>(nativeAbis);
        }
    }
    public static final class Plan {
        public final Part base;
        public final List<Part> splits;
        public final String abi;
        private Plan(Part base, List<Part> splits, String abi) {
            this.base = base; this.splits = Collections.unmodifiableList(splits); this.abi = abi;
        }
    }
    public static Plan select(List<Part> parts, List<String> supportedAbis) {
        if (parts.isEmpty() || parts.size() > 256 || supportedAbis.isEmpty()) {
            throw new IllegalArgumentException("Invalid APK set or host ABI list");
        }
        Part base = null;
        Set<String> names = new HashSet<>();
        for (Part part : parts) {
            if (part.splitName == null) {
                if (base != null) throw new IllegalArgumentException("APK set contains multiple base APKs");
                base = part;
            } else if (!names.add(part.splitName)) {
                throw new IllegalArgumentException("Duplicate split identity: " + part.splitName);
            }
        }
        if (base == null) throw new IllegalArgumentException("APK set contains no base manifest");
        Map<String, Set<String>> abiGroups = new HashMap<>();
        for (Part part : parts) {
            if (!base.packageName.equals(part.packageName) || base.version != part.version) {
                throw new IllegalArgumentException("APK set mixes packages or versions");
            }
            String abi = abiQualifier(part.splitName);
            if (abi != null) {
                String group = part.splitName.substring(0, part.splitName.lastIndexOf('.') + 1);
                abiGroups.computeIfAbsent(group, ignored -> new HashSet<>()).add(abi);
                if (!part.nativeAbis.isEmpty() && !part.nativeAbis.contains(abi)) {
                    throw new IllegalArgumentException("Native libraries contradict split ABI: " + part.splitName);
                }
            }
        }
        String chosen = null;
        for (String candidate : supportedAbis) {
            boolean compatible = true;
            for (Set<String> group : abiGroups.values()) if (!group.contains(candidate)) compatible = false;
            for (Part part : parts) {
                if (abiQualifier(part.splitName) == null && !part.nativeAbis.isEmpty() &&
                    !part.nativeAbis.contains(candidate)) compatible = false;
            }
            if (compatible) { chosen = candidate; break; }
        }
        if (chosen == null) throw new IllegalArgumentException("APK set has no common native ABI supported by this process");
        List<Part> selected = new ArrayList<>();
        for (Part part : parts) {
            if (part == base) continue;
            String abi = abiQualifier(part.splitName);
            if (abi == null || abi.equals(chosen)) selected.add(part);
        }
        selected.sort(Comparator.comparing(part -> part.splitName));
        return new Plan(base, selected, chosen);
    }
    private static String abiQualifier(String splitName) {
        if (splitName == null) return null;
        int config = splitName.lastIndexOf("config.");
        if (config < 0 || (config != 0 && splitName.charAt(config - 1) != '.')) return null;
        String qualifier = splitName.substring(config + 7);
        for (String abi : ABIS) if (qualifier.equals(abi.replace('-', '_'))) return abi;
        return null;
    }
    private SplitApkPlanner() {}
}
