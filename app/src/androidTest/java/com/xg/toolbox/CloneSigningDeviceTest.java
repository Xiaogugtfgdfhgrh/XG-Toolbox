package com.xg.toolbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.xg.toolbox.core.clone.internal.ApkClonePatcher;
import com.xg.toolbox.core.clone.internal.ApkCloneSigner;
import com.xg.toolbox.core.clone.internal.ApkCloneVerifier;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CloneSigningDeviceTest {
    @Test
    public void patchAndSignInstalledApkWithPlatformRsa() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File installed = new File(context.getApplicationInfo().sourceDir);
        assertTrue("installed APK missing", installed.isFile());
        String oldPackage = context.getPackageName();
        String newPackage = differentSameLengthPackage(oldPackage);
        File dir = new File(context.getCacheDir(), "clone-signing-test-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        File unsigned = new File(dir, "unsigned.apk");
        File signed = new File(dir, "signed.apk");
        try {
            ApkClonePatcher.Result patch = ApkClonePatcher.patch(
                    installed, unsigned, oldPackage, newPackage);
            assertTrue(patch.criticalEntries.stream().anyMatch(
                    entry -> "AndroidManifest.xml".equals(entry.name) && entry.patched));
            int minSdk = Math.max(30, context.getApplicationInfo().minSdkVersion);
            ApkCloneSigner.Result signature = ApkCloneSigner.sign(unsigned, signed, minSdk);
            assertNotNull(signature);
            assertTrue("v2/v3 signature missing", signature.verifiedV2 || signature.verifiedV3);
            ApkCloneVerifier.SignatureResult verify = ApkCloneVerifier.verifySignature(signed);
            assertTrue("verify: " + verify.detail, verify.ok);
            assertTrue("v2/v3 verify missing", verify.v2 || verify.v3);
            assertTrue(signed.isFile() && signed.length() > 0);
            PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(
                    signed.getAbsolutePath(),
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_PROVIDERS);
            assertNotNull("PackageManager could not parse clone", archive);
            assertEquals(newPackage, archive.packageName);
            assertTrue("old MainActivity class missing",
                    archive.activities != null && java.util.Arrays.stream(archive.activities)
                            .anyMatch(activity -> MAIN_ACTIVITY.equals(activity.name)));
            assertNotNull(archive.applicationInfo);
            assertEquals(APP_CLASS, archive.applicationInfo.className);
            assertFalse("old package remains in provider authority",
                    archive.providers != null && java.util.Arrays.stream(archive.providers)
                            .flatMap(provider -> provider.authority == null
                                    ? java.util.stream.Stream.empty()
                                    : java.util.Arrays.stream(provider.authority.split(";")))
                            .anyMatch(authority -> authority.contains(oldPackage)));
        } finally {
            deleteTree(dir);
        }
    }

    private static final String MAIN_ACTIVITY = "com.xg.toolbox.MainActivity";
    private static final String APP_CLASS = "com.xg.toolbox.XgApp";

    private static String differentSameLengthPackage(String pkg) {
        char last = pkg.charAt(pkg.length() - 1);
        char replacement = last == 'z' ? 'y' : (last == '9' ? '8' : (char) (last + 1));
        return pkg.substring(0, pkg.length() - 1) + replacement;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
