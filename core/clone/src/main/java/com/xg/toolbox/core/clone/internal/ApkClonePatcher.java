package com.xg.toolbox.core.clone.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites an installed single-APK build into a same-length package clone.
 *
 * <p>The rewrite is deliberately byte-length preserving. Android's binary XML,
 * DEX string data and resources.arsc package name all contain the dotted
 * package name; replacing it with an identically sized ASCII package keeps all
 * string-pool and ULEB128 lengths unchanged.</p>
 *
 * <p>This class is intentionally free of Android APIs so the exact production
 * implementation can be exercised in an ordinary JVM against a real APK.</p>
 */
public final class ApkClonePatcher {
    public static final String ANDROID_MANIFEST = "AndroidManifest.xml";
    public static final String RESOURCES_ARSC = "resources.arsc";
    public static final int PAGE_ALIGNMENT = 16384;
    public static final int ZIP_ALIGNMENT = 4;

    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int CUSTOM_EXTRA_ID = 0x5847; // "XG"
    private static final String AUTHORITY_SUFFIX = ".androidx-startup";
    private static final String DYNAMIC_PERMISSION_SUFFIX =
            ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";

    private ApkClonePatcher() {
    }

    public static final class EntryStat {
        public final String name;
        public final int utf8Occurrences;
        public final int utf16LeOccurrences;
        public final boolean patched;
        public final boolean stored;

        EntryStat(
                String name,
                int utf8Occurrences,
                int utf16LeOccurrences,
                boolean patched,
                boolean stored
        ) {
            this.name = name;
            this.utf8Occurrences = utf8Occurrences;
            this.utf16LeOccurrences = utf16LeOccurrences;
            this.patched = patched;
            this.stored = stored;
        }

        public int totalOccurrences() {
            return utf8Occurrences + utf16LeOccurrences;
        }
    }

    public static final class Result {
        public final File output;
        public final String oldPackage;
        public final String newPackage;
        public final List<EntryStat> criticalEntries;
        public final List<String> removedSignatureEntries;
        public final boolean resourcesPatched;
        public final long outputBytes;

        Result(
                File output,
                String oldPackage,
                String newPackage,
                List<EntryStat> criticalEntries,
                List<String> removedSignatureEntries,
                boolean resourcesPatched,
                long outputBytes
        ) {
            this.output = output;
            this.oldPackage = oldPackage;
            this.newPackage = newPackage;
            this.criticalEntries = Collections.unmodifiableList(criticalEntries);
            this.removedSignatureEntries = Collections.unmodifiableList(removedSignatureEntries);
            this.resourcesPatched = resourcesPatched;
            this.outputBytes = outputBytes;
        }
    }

    private static final class EntryScan {
        final int utf8;
        final int utf16Le;

        EntryScan(int utf8, int utf16Le) {
            this.utf8 = utf8;
            this.utf16Le = utf16Le;
        }

        int total() {
            return utf8 + utf16Le;
        }
    }

    private static final class EntryWork {
        final ZipEntry source;
        final String name;
        File patchedTemp;
        int method;
        int alignment;
        long size;
        long crc;
        EntryScan scan;

        EntryWork(ZipEntry source) {
            this.source = source;
            this.name = source.getName();
        }
    }

    public static Result patch(
            File inputApk,
            File outputApk,
            String oldPackage,
            String newPackage
    ) throws IOException {
        if (inputApk == null || !inputApk.isFile()) {
            throw new IOException("输入 APK 不存在: " + inputApk);
        }
        if (outputApk == null) {
            throw new IOException("输出 APK 路径为空");
        }
        validatePackages(oldPackage, newPackage);

        File absoluteOutput = outputApk.getAbsoluteFile();
        File parent = absoluteOutput.getParentFile();
        if (parent == null) {
            throw new IOException("输出 APK 没有父目录");
        }
        Files.createDirectories(parent.toPath());
        Files.deleteIfExists(absoluteOutput.toPath());

        File workDir = Files.createTempDirectory(parent.toPath(), "xg-clone-patch-").toFile();
        boolean success = false;
        try {
            byte[] oldUtf8 = oldPackage.getBytes(StandardCharsets.UTF_8);
            byte[] newUtf8 = newPackage.getBytes(StandardCharsets.UTF_8);
            byte[] oldUtf16 = oldPackage.getBytes(StandardCharsets.UTF_16LE);
            byte[] newUtf16 = newPackage.getBytes(StandardCharsets.UTF_16LE);

            List<EntryWork> entries = new ArrayList<>();
            List<EntryStat> criticalStats = new ArrayList<>();
            List<String> removedSignatures = new ArrayList<>();
            Set<String> seenNames = new HashSet<>();

            boolean manifestFound = false;
            boolean resourcesPatched = false;
            boolean dexFound = false;

            try (ZipFile zip = new ZipFile(inputApk)) {
                for (ZipEntry source : Collections.list(zip.entries())) {
                    if (source.isDirectory()) {
                        continue;
                    }
                    String name = source.getName();
                    validateEntryName(name);
                    if (!seenNames.add(name)) {
                        throw new IOException("APK 包含重复 ZIP 条目，拒绝重打包: " + name);
                    }
                    if (isV1SignatureFile(name)) {
                        removedSignatures.add(name);
                        continue;
                    }

                    EntryWork work = new EntryWork(source);
                    work.method = chooseMethod(name, source.getMethod());
                    work.alignment = alignmentFor(name, work.method);
                    work.size = source.getSize();
                    work.crc = source.getCrc();

                    if (isDexEntry(name)) {
                        dexFound = true;
                    }
                    if (isCriticalPatchedEntry(name)) {
                        EntryScan scan = scanEntry(zip, source, oldUtf8, oldUtf16);
                        work.scan = scan;
                        if (ANDROID_MANIFEST.equals(name)) {
                            manifestFound = true;
                            if (scan.total() == 0) {
                                throw new IOException(
                                        "AndroidManifest.xml 未找到旧包名，拒绝生成不完整克隆"
                                );
                            }
                        }
                        if (scan.total() > 0) {
                            File temp = File.createTempFile("entry-", ".bin", workDir);
                            copyEntryToFile(zip, source, temp);
                            patchManifestPackageStrings(temp, oldPackage, newPackage);
                            work.patchedTemp = temp;
                            work.size = temp.length();
                            work.crc = crc32OfFile(temp);
                        }
                        work.size = ensureKnownSize(zip, source, work, workDir);
                        criticalStats.add(new EntryStat(
                                name,
                                scan.utf8,
                                scan.utf16Le,
                                work.patchedTemp != null,
                                work.method == ZipEntry.STORED
                        ));
                    }
                    entries.add(work);
                }

                if (removedSignatures.contains(ANDROID_MANIFEST)) {
                    throw new IOException("签名过滤逻辑错误：误删 AndroidManifest.xml");
                }
                if (!manifestFound) {
                    throw new IOException("APK 缺少 AndroidManifest.xml");
                }
                if (!dexFound) {
                    throw new IOException("APK 缺少根目录 classes*.dex，无法安全克隆");
                }

                writeZip(zip, entries, absoluteOutput);
            }

            if (criticalStats.isEmpty()) {
                throw new IOException("未重写任何关键条目");
            }
            ApkCloneVerifier.AlignmentResult alignment = ApkCloneVerifier.verifyAlignment(absoluteOutput);
            if (!alignment.ok) {
                throw new IOException("重打包 ZIP 对齐校验失败: " + alignment.detail);
            }

            Result result = new Result(
                    absoluteOutput,
                    oldPackage,
                    newPackage,
                    new ArrayList<>(criticalStats),
                    new ArrayList<>(removedSignatures),
                    resourcesPatched,
                    Files.size(absoluteOutput.toPath())
            );
            success = true;
            return result;
        } finally {
            if (workDir.exists()) {
                deleteRecursively(workDir);
            }
            if (!success) {
                Files.deleteIfExists(absoluteOutput.toPath());
            }
        }
    }

    private static void validatePackages(String oldPackage, String newPackage) throws IOException {
        if (oldPackage == null || oldPackage.isEmpty()) {
            throw new IOException("旧包名为空");
        }
        if (newPackage == null || newPackage.isEmpty()) {
            throw new IOException("新包名为空");
        }
        if (oldPackage.equals(newPackage)) {
            throw new IOException("新包名不能与旧包名相同");
        }
        if (oldPackage.getBytes(StandardCharsets.UTF_8).length
                != newPackage.getBytes(StandardCharsets.UTF_8).length) {
            throw new IOException("包名 UTF-8 字节长度不一致，无法原位替换");
        }
        if (oldPackage.getBytes(StandardCharsets.UTF_16LE).length
                != newPackage.getBytes(StandardCharsets.UTF_16LE).length) {
            throw new IOException("包名 UTF-16LE 字节长度不一致，无法原位替换");
        }
        String[] oldSegments = oldPackage.split("\\.", -1);
        String[] newSegments = newPackage.split("\\.", -1);
        if (oldSegments.length != newSegments.length) {
            throw new IOException("包名段数不一致");
        }
        for (int i = 0; i < oldSegments.length; i++) {
            if (oldSegments[i].length() != newSegments[i].length()) {
                throw new IOException("包名第 " + (i + 1) + " 段长度不一致");
            }
        }
        if (!newPackage.matches("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")) {
            throw new IOException("新包名不是合法的小写 Android 包名: " + newPackage);
        }
    }

    private static void validateEntryName(String name) throws IOException {
        if (name.isEmpty() || name.indexOf('\0') >= 0 || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.startsWith("../") || name.contains("/../")) {
            throw new IOException("拒绝不安全的 APK 条目名: " + name);
        }
    }

    private static void patchManifestPackageStrings(
            File manifest,
            String oldPackage,
            String newPackage
    ) throws IOException {
        byte[] oldPkgUtf8 = oldPackage.getBytes(StandardCharsets.UTF_8);
        byte[] newPkgUtf8 = newPackage.getBytes(StandardCharsets.UTF_8);
        byte[] oldPkgUtf16 = oldPackage.getBytes(StandardCharsets.UTF_16LE);
        byte[] newPkgUtf16 = newPackage.getBytes(StandardCharsets.UTF_16LE);
        int changed = 0;
        changed += replaceBoundedInFile(manifest, oldPkgUtf8, newPkgUtf8);
        changed += replaceBoundedInFile(manifest, oldPkgUtf16, newPkgUtf16);
        changed += replacePackageScopedString(
                manifest, oldPackage, newPackage, AUTHORITY_SUFFIX);
        changed += replacePackageScopedString(
                manifest, oldPackage, newPackage, DYNAMIC_PERMISSION_SUFFIX);
        if (changed == 0) {
            throw new IOException("AndroidManifest.xml 未找到可替换的包名/authority");
        }
        if (countBoundedInFile(manifest, oldPkgUtf8) != 0
                || countBoundedInFile(manifest, oldPkgUtf16) != 0) {
            throw new IOException("AndroidManifest.xml package 属性仍残留旧包名");
        }
        if (countBoundedInFile(manifest, (oldPackage + AUTHORITY_SUFFIX)
                .getBytes(StandardCharsets.UTF_8)) != 0
                || countBoundedInFile(manifest, (oldPackage + DYNAMIC_PERMISSION_SUFFIX)
                .getBytes(StandardCharsets.UTF_8)) != 0) {
            throw new IOException("AndroidManifest.xml authority/permission 仍残留旧包名");
        }
    }

    private static int replacePackageScopedString(
            File manifest,
            String oldPackage,
            String newPackage,
            String suffix
    ) throws IOException {
        byte[] oldUtf8 = (oldPackage + suffix).getBytes(StandardCharsets.UTF_8);
        byte[] newUtf8 = (newPackage + suffix).getBytes(StandardCharsets.UTF_8);
        byte[] oldUtf16 = (oldPackage + suffix).getBytes(StandardCharsets.UTF_16LE);
        byte[] newUtf16 = (newPackage + suffix).getBytes(StandardCharsets.UTF_16LE);
        return replaceBoundedInFile(manifest, oldUtf8, newUtf8)
                + replaceBoundedInFile(manifest, oldUtf16, newUtf16);
    }

    private static int replaceBoundedInFile(
            File file,
            byte[] needle,
            byte[] replacement
    ) throws IOException {
        if (needle.length != replacement.length) {
            throw new IOException("原位替换要求长度一致");
        }
        byte[] data = Files.readAllBytes(file.toPath());
        int changed = 0;
        for (int i = 0; i + needle.length <= data.length; i++) {
            if (!matchesAt(data, i, needle)) continue;
            int before = i - 1;
            int after = i + needle.length;
            if (before >= 0 && isPackageNameByte(data[before])) continue;
            if (after < data.length && isPackageNameByte(data[after])) continue;
            System.arraycopy(replacement, 0, data, i, replacement.length);
            changed++;
            i += needle.length - 1;
        }
        if (changed > 0) Files.write(file.toPath(), data);
        return changed;
    }

    private static int countBoundedInFile(File file, byte[] needle) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        int count = 0;
        for (int i = 0; i + needle.length <= data.length; i++) {
            if (!matchesAt(data, i, needle)) continue;
            int before = i - 1;
            int after = i + needle.length;
            if (before >= 0 && isPackageNameByte(data[before])) continue;
            if (after < data.length && isPackageNameByte(data[after])) continue;
            count++;
        }
        return count;
    }

    private static boolean isPackageNameByte(byte value) {
        int c = value & 0xFF;
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '.' || c == '$';
    }

    static boolean isV1SignatureFile(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        String leaf = upper.substring("META-INF/".length());
        if (leaf.isEmpty() || leaf.indexOf('/') >= 0) {
            return false;
        }
        return "MANIFEST.MF".equals(leaf)
                || leaf.endsWith(".SF")
                || leaf.endsWith(".RSA")
                || leaf.endsWith(".DSA")
                || leaf.endsWith(".EC");
    }

    private static boolean isCriticalPatchedEntry(String name) {
        // Device-verified constraint: only the binary AndroidManifest.xml may be
        // patched. Rewriting DEX string pools (even same-length) invalidates
        // DEX string_ids ordering requirements; resources.arsc is likewise left
        // untouched. All package-scoped authorities/permissions live in AXML.
        return ANDROID_MANIFEST.equals(name);
    }

    private static boolean isDexEntry(String name) {
        return name.matches("classes(?:[0-9]+)?\\.dex");
    }

    private static boolean isNativeLibrary(String name) {
        return name.startsWith("lib/") && name.endsWith(".so");
    }

    private static int chooseMethod(String name, int sourceMethod) throws IOException {
        if (sourceMethod != ZipEntry.STORED && sourceMethod != ZipEntry.DEFLATED) {
            throw new IOException("不支持的 ZIP 压缩方法 " + sourceMethod + ": " + name);
        }
        if (isNativeLibrary(name)
                || RESOURCES_ARSC.equals(name)
                || isDexEntry(name)) {
            return ZipEntry.STORED;
        }
        return sourceMethod;
    }

    private static int alignmentFor(String name, int method) {
        if (method != ZipEntry.STORED) {
            return 1;
        }
        return isNativeLibrary(name) ? PAGE_ALIGNMENT : ZIP_ALIGNMENT;
    }

    private static long ensureKnownSize(ZipFile zip, ZipEntry source, EntryWork work, File workDir)
            throws IOException {
        if (work.size >= 0) {
            return work.size;
        }
        File temp = File.createTempFile("unknown-size-", ".bin", workDir);
        try {
            copyEntryToFile(zip, source, temp);
            work.size = temp.length();
            work.crc = crc32OfFile(temp);
            if (work.patchedTemp == null) {
                work.patchedTemp = temp;
                temp = null;
            }
            return work.size;
        } finally {
            if (temp != null && temp.exists()) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static EntryScan scanEntry(
            ZipFile zip,
            ZipEntry entry,
            byte[] oldUtf8,
            byte[] oldUtf16Le
    ) throws IOException {
        BytePatternMatcher utf8 = new BytePatternMatcher(oldUtf8);
        BytePatternMatcher utf16 = new BytePatternMatcher(oldUtf16Le);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry), BUFFER_SIZE)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                for (int i = 0; i < read; i++) {
                    int b = buffer[i] & 0xFF;
                    utf8.feed(b);
                    utf16.feed(b);
                }
            }
        }
        return new EntryScan(utf8.count, utf16.count);
    }

    private static void copyEntryToFile(ZipFile zip, ZipEntry entry, File destination)
            throws IOException {
        CRC32 crc = new CRC32();
        long total = 0L;
        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry), BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                crc.update(buffer, 0, read);
                total += read;
            }
        }
        if (entry.getSize() >= 0 && total != entry.getSize()) {
            throw new IOException("APK 条目解压大小校验失败: " + entry.getName()
                    + " expected=" + entry.getSize() + " actual=" + total);
        }
        if (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()) {
            throw new IOException("APK 条目 CRC 校验失败: " + entry.getName());
        }
    }

    private static long crc32OfFile(File file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    private static int countInFile(File file, byte[] needle) throws IOException {
        BytePatternMatcher matcher = new BytePatternMatcher(needle);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                for (int i = 0; i < read; i++) {
                    matcher.feed(buffer[i] & 0xFF);
                }
            }
        }
        return matcher.count;
    }

    private static int replaceAllInFile(File file, byte[] needle, byte[] replacement)
            throws IOException {
        if (needle.length == 0) {
            throw new IOException("替换模式为空");
        }
        if (needle.length != replacement.length) {
            throw new IOException("原位替换要求长度一致");
        }
        FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            long size = channel.size();
            int overlap = needle.length - 1;
            byte[] buffer = new byte[BUFFER_SIZE + Math.max(0, overlap)];
            byte[] replacementCopy = replacement.clone();
            long position = 0;
            int replaced = 0;
            while (position < size) {
                int wanted = (int) Math.min(buffer.length, size - position);
                ByteBuffer readBuffer = ByteBuffer.wrap(buffer, 0, wanted);
                readFully(channel, readBuffer, position);
                int lastStart = wanted - needle.length;
                for (int i = 0; i <= lastStart; i++) {
                    if (matchesAt(buffer, i, needle)) {
                        writeFully(channel, ByteBuffer.wrap(replacementCopy), position + i);
                        replaced++;
                        i += needle.length - 1;
                    }
                }
                if (position + wanted >= size) {
                    break;
                }
                int advance = wanted - overlap;
                if (advance <= 0) {
                    throw new IOException("内部错误：扫描窗口太小");
                }
                position += advance;
            }
            channel.force(true);
            return replaced;
        } finally {
            channel.close();
        }
    }

    private static boolean matchesAt(byte[] haystack, int offset, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (haystack[offset + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    private static void readFully(FileChannel channel, ByteBuffer destination, long position)
            throws IOException {
        long cursor = position;
        while (destination.hasRemaining()) {
            int read = channel.read(destination, cursor);
            if (read < 0) {
                throw new IOException("读取 APK 条目时提前 EOF");
            }
            if (read == 0) {
                Thread.yield();
                continue;
            }
            cursor += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source, long position)
            throws IOException {
        long cursor = position;
        while (source.hasRemaining()) {
            int written = channel.write(source, cursor);
            if (written <= 0) {
                Thread.yield();
                continue;
            }
            cursor += written;
        }
    }

    private static void writeZip(
            ZipFile sourceZip,
            List<EntryWork> entries,
            File output
    ) throws IOException {
        try (CountingOutputStream counting = new CountingOutputStream(
                new BufferedOutputStream(new FileOutputStream(output), BUFFER_SIZE));
             ZipOutputStream zipOut = new ZipOutputStream(counting, StandardCharsets.UTF_8)) {
            zipOut.setLevel(6);
            for (EntryWork work : entries) {
                String name = work.name;
                ZipEntry outEntry = new ZipEntry(name);
                if (work.source.getTime() >= 0) {
                    outEntry.setTime(work.source.getTime());
                }
                String comment = work.source.getComment();
                if (comment != null) {
                    outEntry.setComment(comment);
                }

                if (work.method == ZipEntry.STORED) {
                    int nameBytes = name.getBytes(StandardCharsets.UTF_8).length;
                    byte[] extra = buildAlignmentExtra(
                            counting.getCount(),
                            30 + nameBytes,
                            work.alignment
                    );
                    if (extra.length > 0) {
                        outEntry.setExtra(extra);
                    }
                    outEntry.setMethod(ZipEntry.STORED);
                    outEntry.setSize(work.size);
                    outEntry.setCompressedSize(work.size);
                    outEntry.setCrc(work.crc);
                } else {
                    outEntry.setMethod(ZipEntry.DEFLATED);
                    if (work.size >= 0) {
                        outEntry.setSize(work.size);
                    }
                    if (work.crc >= 0) {
                        outEntry.setCrc(work.crc);
                    }
                }

                zipOut.putNextEntry(outEntry);
                CRC32 writtenCrc = new CRC32();
                long written = 0L;
                try (InputStream in = work.patchedTemp != null
                        ? new BufferedInputStream(new FileInputStream(work.patchedTemp), BUFFER_SIZE)
                        : new BufferedInputStream(sourceZip.getInputStream(work.source), BUFFER_SIZE)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        zipOut.write(buffer, 0, read);
                        writtenCrc.update(buffer, 0, read);
                        written += read;
                    }
                }
                if (written != work.size) {
                    throw new IOException("写入条目大小不一致: " + name
                            + " expected=" + work.size + " actual=" + written);
                }
                if (work.crc >= 0 && writtenCrc.getValue() != work.crc) {
                    throw new IOException("写入条目 CRC 不一致: " + name);
                }
                zipOut.closeEntry();
            }
            zipOut.finish();
        }
    }

    private static byte[] buildAlignmentExtra(long localHeaderOffset, int headerBase, int alignment)
            throws IOException {
        if (alignment <= 1) {
            return new byte[0];
        }
        long remainder = (localHeaderOffset + headerBase) % alignment;
        int rawPad = (int) ((alignment - remainder) % alignment);
        int extraLength;
        if (rawPad == 0) {
            extraLength = 0;
        } else if (rawPad >= 4) {
            extraLength = rawPad;
        } else {
            extraLength = rawPad + alignment;
        }
        if (extraLength == 0) {
            return new byte[0];
        }
        if (extraLength > 0xFFFF || extraLength < 4) {
            throw new IOException("ZIP 对齐 extra 字段长度非法: " + extraLength);
        }
        byte[] extra = new byte[extraLength];
        extra[0] = (byte) (CUSTOM_EXTRA_ID & 0xFF);
        extra[1] = (byte) ((CUSTOM_EXTRA_ID >>> 8) & 0xFF);
        int dataLength = extraLength - 4;
        extra[2] = (byte) (dataLength & 0xFF);
        extra[3] = (byte) ((dataLength >>> 8) & 0xFF);
        return extra;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static final class BytePatternMatcher {
        private final byte[] pattern;
        private final int[] prefix;
        private int matched;
        int count;

        BytePatternMatcher(byte[] pattern) {
            if (pattern == null || pattern.length == 0) {
                throw new IllegalArgumentException("pattern is empty");
            }
            this.pattern = pattern;
            this.prefix = new int[pattern.length];
            int k = 0;
            for (int i = 1; i < pattern.length; i++) {
                while (k > 0 && pattern[i] != pattern[k]) {
                    k = prefix[k - 1];
                }
                if (pattern[i] == pattern[k]) {
                    k++;
                }
                prefix[i] = k;
            }
        }

        void feed(int value) {
            byte b = (byte) value;
            while (matched > 0 && b != pattern[matched]) {
                matched = prefix[matched - 1];
            }
            if (b == pattern[matched]) {
                matched++;
            }
            if (matched == pattern.length) {
                count++;
                matched = prefix[matched - 1];
            }
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        long getCount() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}



