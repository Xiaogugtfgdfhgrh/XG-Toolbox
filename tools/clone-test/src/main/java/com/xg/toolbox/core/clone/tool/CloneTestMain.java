package com.xg.toolbox.core.clone.tool;

import com.xg.toolbox.core.clone.internal.ApkClonePatcher;
import com.xg.toolbox.core.clone.internal.ApkCloneSigner;
import com.xg.toolbox.core.clone.internal.ApkCloneVerifier;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

public final class CloneTestMain {
    public static void main(String[] args) throws Exception {
        String inputPath = required("CLONE_TEST_INPUT");
        String outputPath = required("CLONE_TEST_OUTPUT");
        String oldPackage = required("CLONE_TEST_OLD");
        String newPackage = required("CLONE_TEST_NEW");

        File input = new File(inputPath);
        File outputDir = new File(outputPath);
        Files.createDirectories(outputDir.toPath());
        File unsigned = new File(outputDir, "xg-clone-unsigned.apk");
        File signed = new File(outputDir, "xg-clone-signed.apk");

        System.out.println("input=" + input.getAbsolutePath());
        System.out.println("inputBytes=" + input.length());
        System.out.println("oldPackage=" + oldPackage + " length=" + oldPackage.length());
        System.out.println("newPackage=" + newPackage + " length=" + newPackage.length());

        long started = System.currentTimeMillis();
        ApkClonePatcher.Result patch = ApkClonePatcher.patch(input, unsigned, oldPackage, newPackage);
        System.out.println("patchMillis=" + (System.currentTimeMillis() - started));
        System.out.println("unsigned=" + unsigned.getAbsolutePath());
        System.out.println("unsignedBytes=" + unsigned.length());
        System.out.println("removedV1Entries=" + patch.removedSignatureEntries);
        System.out.println("resourcesPatched=" + patch.resourcesPatched);
        for (ApkClonePatcher.EntryStat entry : patch.criticalEntries) {
            System.out.println("entry=" + entry.name
                    + " utf8=" + entry.utf8Occurrences
                    + " utf16le=" + entry.utf16LeOccurrences
                    + " patched=" + entry.patched
                    + " stored=" + entry.stored);
        }

        ApkCloneVerifier.AlignmentResult unsignedAlignment =
                ApkCloneVerifier.verifyAlignment(unsigned);
        System.out.println("unsignedAlignmentOk=" + unsignedAlignment.ok
                + " detail=" + unsignedAlignment.detail);

        started = System.currentTimeMillis();
        ApkCloneSigner.Result signature = ApkCloneSigner.sign(unsigned, signed, 30);
        System.out.println("signMillis=" + (System.currentTimeMillis() - started));
        System.out.println("signed=" + signed.getAbsolutePath());
        System.out.println("signedBytes=" + signed.length());
        System.out.println("certificateSha256=" + signature.certificateSha256);
        System.out.println("signatureV1=" + signature.verifiedV1);
        System.out.println("signatureV2=" + signature.verifiedV2);
        System.out.println("signatureV3=" + signature.verifiedV3);
        System.out.println("alignment=" + signature.alignmentDetail);

        ApkCloneVerifier.SignatureResult finalSignature =
                ApkCloneVerifier.verifySignature(signed);
        ApkCloneVerifier.AlignmentResult finalAlignment =
                ApkCloneVerifier.verifyAlignment(signed);
        if (!finalSignature.ok) {
            throw new IllegalStateException("final signature check failed: " + finalSignature.detail);
        }
        if (!finalAlignment.ok) {
            throw new IllegalStateException("final alignment check failed: " + finalAlignment.detail);
        }
        System.out.println("finalSignatureOk=true");
        System.out.println("finalV1=" + finalSignature.v1);
        System.out.println("finalV2=" + finalSignature.v2);
        System.out.println("finalV3=" + finalSignature.v3);
        System.out.println("finalAlignmentOk=" + finalAlignment.ok);
        System.out.println("finalAlignmentDetail=" + finalAlignment.detail);
        System.out.println("sha256=" + sha256(signed));
        System.out.println("RESULT=OK");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("missing environment variable " + name);
        }
        return value;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return builder.toString();
    }
}

