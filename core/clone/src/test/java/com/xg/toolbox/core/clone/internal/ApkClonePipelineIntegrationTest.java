package com.xg.toolbox.core.clone.internal;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Assume;
import org.junit.Test;

public class ApkClonePipelineIntegrationTest {
    @Test
    public void patchAndSignRealReleaseApk() throws Exception {
        String sample = System.getenv("XG_CLONE_SAMPLE_APK");
        Assume.assumeTrue(sample != null && new File(sample).isFile());
        File source = new File(sample);
        File dir = Files.createTempDirectory("xg-clone-pipeline-").toFile();
        File unsigned = new File(dir, "unsigned.apk");
        File signed = new File(dir, "signed.apk");
        try {
            ApkClonePatcher.Result patch = ApkClonePatcher.patch(
                    source, unsigned, "com.xg.toolbox", "com.xg.toolbxx");
            assertTrue(patch.criticalEntries.stream().anyMatch(
                    entry -> "AndroidManifest.xml".equals(entry.name) && entry.patched));
            assertTrue("MainActivity class was renamed",
                    manifestContains(unsigned, "com.xg.toolbox.MainActivity"));
            assertTrue("XgApp class was renamed",
                    manifestContains(unsigned, "com.xg.toolbox.XgApp"));
            assertTrue("new package/authority was not written",
                    manifestContains(unsigned, "com.xg.toolbxx.androidx-startup"));
            verifyWithAapt2IfConfigured(unsigned);
            ApkCloneSigner.Result signature = ApkCloneSigner.sign(unsigned, signed, 30);
            assertTrue(signature.verifiedV2 || signature.verifiedV3);
            assertTrue(signature.alignmentDetail, !signature.alignmentDetail.isEmpty());
        } finally {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    child.delete();
                }
            }
            dir.delete();
        }
    }
    private static boolean manifestContains(File apk, String value) throws Exception {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) return false;
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] data = input.readAllBytes();
                return indexOf(data, value.getBytes(StandardCharsets.UTF_8)) >= 0
                        || indexOf(data, value.getBytes(StandardCharsets.UTF_16LE)) >= 0;
            }
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void verifyWithAapt2IfConfigured(File apk) throws Exception {
        String aapt2 = System.getenv("XG_AAPT2");
        if (aapt2 == null || !new File(aapt2).isFile()) return;
        Process process = new ProcessBuilder(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk.getAbsolutePath())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertTrue("aapt2 exit=" + exit + "\n" + output, exit == 0);
        assertTrue("aapt2 lost MainActivity\n" + output,
                output.contains("com.xg.toolbox.MainActivity"));
        assertTrue("aapt2 lost XgApp\n" + output,
                output.contains("com.xg.toolbox.XgApp"));
        assertTrue("aapt2 lost new authority\n" + output,
                output.contains("com.xg.toolbxx.androidx-startup"));
    }

}
