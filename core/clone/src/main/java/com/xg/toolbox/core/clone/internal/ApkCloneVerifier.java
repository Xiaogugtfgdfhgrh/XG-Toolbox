package com.xg.toolbox.core.clone.internal;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Independent checks for the produced APK: signing schemes and stored-entry alignment. */
public final class ApkCloneVerifier {
    private ApkCloneVerifier() {
    }

    public static final class SignatureResult {
        public final boolean ok;
        public final boolean v1;
        public final boolean v2;
        public final boolean v3;
        public final List<String> certificateSha256;
        public final String detail;

        SignatureResult(
                boolean ok,
                boolean v1,
                boolean v2,
                boolean v3,
                List<String> certificateSha256,
                String detail
        ) {
            this.ok = ok;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
            this.certificateSha256 = Collections.unmodifiableList(certificateSha256);
            this.detail = detail;
        }

        public boolean hasAllRequestedSchemes() {
            return v2 || v3;
        }
    }

    public static final class AlignmentResult {
        public final boolean ok;
        public final int storedEntriesChecked;
        public final int nativeLibrariesChecked;
        public final boolean resourcesArscStored;
        public final String detail;

        AlignmentResult(
                boolean ok,
                int storedEntriesChecked,
                int nativeLibrariesChecked,
                boolean resourcesArscStored,
                String detail
        ) {
            this.ok = ok;
            this.storedEntriesChecked = storedEntriesChecked;
            this.nativeLibrariesChecked = nativeLibrariesChecked;
            this.resourcesArscStored = resourcesArscStored;
            this.detail = detail;
        }
    }

    public static SignatureResult verifySignature(File apk) {
        try {
            ApkVerifier.Result result = new ApkVerifier.Builder(apk).build().verify();
            List<String> fingerprints = new ArrayList<>();
            for (X509Certificate certificate : result.getSignerCertificates()) {
                fingerprints.add(sha256Hex(certificate.getEncoded()));
            }
            String detail;
            if (result.isVerified()) {
                detail = "verified signerCertificates=" + fingerprints.size();
            } else {
                StringBuilder builder = new StringBuilder("verify failed: ");
                if (result.getErrors().isEmpty()) {
                    builder.append("unknown");
                } else {
                    for (int i = 0; i < result.getErrors().size(); i++) {
                        if (i > 0) {
                            builder.append("; ");
                        }
                        builder.append(result.getErrors().get(i));
                    }
                }
                detail = builder.toString();
            }
            return new SignatureResult(
                    result.isVerified(),
                    result.isVerifiedUsingV1Scheme(),
                    result.isVerifiedUsingV2Scheme(),
                    result.isVerifiedUsingV3Scheme(),
                    fingerprints,
                    detail
            );
        } catch (Exception e) {
            return new SignatureResult(
                    false,
                    false,
                    false,
                    false,
                    Collections.emptyList(),
                    "verify exception: " + e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
    }

    public static AlignmentResult verifyAlignment(File apk) {
        List<String> issues = new ArrayList<>();
        int storedChecked = 0;
        int nativeChecked = 0;
        boolean resourcesStored = false;
        try (RandomAccessFile file = new RandomAccessFile(apk, "r")) {
            long fileLength = file.length();
            int tailLength = (int) Math.min(fileLength, 65557L);
            byte[] tail = new byte[tailLength];
            file.seek(fileLength - tailLength);
            file.readFully(tail);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (u32(tail, i) == 0x06054b50L) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                return new AlignmentResult(false, 0, 0, false, "EOCD not found");
            }
            int entryCount = u16(tail, eocd + 10);
            long centralSize = u32(tail, eocd + 12);
            long centralOffset = u32(tail, eocd + 16);
            if (entryCount == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
                return new AlignmentResult(false, 0, 0, false, "ZIP64 central directory is unsupported");
            }
            if (centralOffset + centralSize > fileLength) {
                return new AlignmentResult(false, 0, 0, false, "central directory out of range");
            }

            Set<String> names = new HashSet<>();
            long cursor = centralOffset;
            byte[] fixed = new byte[46];
            for (int index = 0; index < entryCount; index++) {
                file.seek(cursor);
                file.readFully(fixed);
                if (u32(fixed, 0) != 0x02014b50L) {
                    issues.add("bad central header at entry " + index);
                    break;
                }
                int method = u16(fixed, 10);
                int nameLength = u16(fixed, 28);
                int extraLength = u16(fixed, 30);
                int commentLength = u16(fixed, 32);
                long localOffset = u32(fixed, 42);
                byte[] nameBytes = new byte[nameLength];
                file.readFully(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                if (!names.add(name)) {
                    issues.add("duplicate entry " + name);
                }
                if (localOffset + 30L > fileLength) {
                    issues.add("local header out of range: " + name);
                    cursor += 46L + nameLength + extraLength + commentLength;
                    continue;
                }
                file.seek(localOffset);
                byte[] local = new byte[30];
                file.readFully(local);
                if (u32(local, 0) != 0x04034b50L) {
                    issues.add("bad local header: " + name);
                    cursor += 46L + nameLength + extraLength + commentLength;
                    continue;
                }
                int localNameLength = u16(local, 26);
                int localExtraLength = u16(local, 28);
                long dataOffset = localOffset + 30L + localNameLength + localExtraLength;

                if (method == 0) {
                    storedChecked++;
                    int required = name.startsWith("lib/") && name.endsWith(".so")
                            ? ApkClonePatcher.PAGE_ALIGNMENT
                            : ApkClonePatcher.ZIP_ALIGNMENT;
                    if (dataOffset % required != 0) {
                        issues.add(name + " dataOffset=" + dataOffset + " not aligned to " + required);
                    }
                    if (name.startsWith("lib/") && name.endsWith(".so")) {
                        nativeChecked++;
                    }
                } else if (name.startsWith("lib/") && name.endsWith(".so")) {
                    issues.add("native library is compressed: " + name);
                }

                if (name.equals(ApkClonePatcher.RESOURCES_ARSC)) {
                    resourcesStored = method == 0;
                    if (method != 0) {
                        issues.add("resources.arsc is compressed");
                    }
                }
                cursor += 46L + nameLength + extraLength + commentLength;
            }
        } catch (Exception e) {
            return new AlignmentResult(
                    false,
                    0,
                    0,
                    false,
                    "alignment check exception: " + e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
        boolean ok = issues.isEmpty();
        String detail = ok
                ? "storedEntries=" + storedChecked + " nativeLibraries=" + nativeChecked
                : String.join("; ", issues);
        return new AlignmentResult(ok, storedChecked, nativeChecked, resourcesStored, detail);
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF)
                | (((long) data[offset + 1] & 0xFF) << 8)
                | (((long) data[offset + 2] & 0xFF) << 16)
                | (((long) data[offset + 3] & 0xFF) << 24);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return builder.toString();
    }
}
