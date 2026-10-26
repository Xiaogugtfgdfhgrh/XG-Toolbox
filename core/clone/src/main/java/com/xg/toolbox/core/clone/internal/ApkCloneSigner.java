package com.xg.toolbox.core.clone.internal;

import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates a fresh RSA-2048 key + self-signed X.509 certificate and signs the
 * patched APK with APK Signature Scheme v2 and v3.
 *
 * <p>The key is intentionally ephemeral. It is never written to the old app's
 * files and therefore cannot be recovered by the old process after uninstall.</p>
 */
public final class ApkCloneSigner {
    private ApkCloneSigner() {
    }

    public static final class Result {
        public final File apk;
        public final X509Certificate certificate;
        public final String certificateSha256;
        public final boolean verifiedV1;
        public final boolean verifiedV2;
        public final boolean verifiedV3;
        public final String alignmentDetail;

        Result(
                File apk,
                X509Certificate certificate,
                String certificateSha256,
                boolean verifiedV1,
                boolean verifiedV2,
                boolean verifiedV3,
                String alignmentDetail
        ) {
            this.apk = apk;
            this.certificate = certificate;
            this.certificateSha256 = certificateSha256;
            this.verifiedV1 = verifiedV1;
            this.verifiedV2 = verifiedV2;
            this.verifiedV3 = verifiedV3;
            this.alignmentDetail = alignmentDetail;
        }
    }

    public static Result sign(File unsignedApk, File signedApk, int minSdk) throws Exception {
        if (unsignedApk == null || !unsignedApk.isFile()) {
            throw new IOException("待签名 APK 不存在: " + unsignedApk);
        }
        if (signedApk == null) {
            throw new IOException("签名输出路径为空");
        }
        if (minSdk < 1) {
            throw new IOException("minSdk 非法: " + minSdk);
        }
        File absoluteOutput = signedApk.getAbsoluteFile();
        File parent = absoluteOutput.getParentFile();
        if (parent == null) {
            throw new IOException("签名输出没有父目录");
        }
        Files.createDirectories(parent.toPath());
        Files.deleteIfExists(absoluteOutput.toPath());

        SecureRandom random = new SecureRandom();
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048, random);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        X509Certificate certificate = createSelfSignedCertificate(keyPair, random);
        certificate.checkValidity();
        certificate.verify(keyPair.getPublic());

        int signingMinSdk = Math.max(minSdk, 30);
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "xg-self-clone",
                keyPair.getPrivate(),
                Collections.singletonList(certificate)
        ).build();

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(absoluteOutput)
                .setMinSdkVersion(signingMinSdk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setDebuggableApkPermitted(true)
                .setAlignmentPreserved(true)
                .setLibraryPageAlignmentBytes(ApkClonePatcher.PAGE_ALIGNMENT)
                .setCreatedBy("xg-toolbox-self-clone")
                .build();
        try {
            signer.sign();
        } catch (Throwable failure) {
            throw new IOException(
                    "apksig v2/v3 signing failed: " + failure.getClass().getName() + ": " + failure.getMessage(),
                    failure
            );
        }

        ApkCloneVerifier.SignatureResult signature = ApkCloneVerifier.verifySignature(absoluteOutput);
        if (!signature.ok) {
            Files.deleteIfExists(absoluteOutput.toPath());
            throw new IOException("apksig 自校验失败: " + signature.detail);
        }
        ApkCloneVerifier.AlignmentResult alignment = ApkCloneVerifier.verifyAlignment(absoluteOutput);
        if (!alignment.ok) {
            Files.deleteIfExists(absoluteOutput.toPath());
            throw new IOException("apksig 输出 ZIP 对齐失效: " + alignment.detail);
        }

        return new Result(
                absoluteOutput,
                certificate,
                sha256Hex(certificate.getEncoded()),
                signature.v1,
                signature.v2,
                signature.v3,
                alignment.detail
        );
    }

    static X509Certificate createSelfSignedCertificate(
            KeyPair keyPair,
            SecureRandom random
    ) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 60L * 60L * 1000L);
        Date notAfter = new Date(now + 30L * 365L * 24L * 60L * 60L * 1000L);
        BigInteger serial = new BigInteger(159, random).add(BigInteger.ONE);
        if (serial.signum() <= 0) {
            serial = BigInteger.ONE;
        }
        String subject = "CN=XG-SelfClone-" + Long.toHexString(random.nextLong());
        X500Name name = new X500Name(subject);
        JcaX509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                name,
                serial,
                notBefore,
                notAfter,
                name,
                keyPair.getPublic()
        );
        certificateBuilder.addExtension(
                Extension.basicConstraints,
                true,
                new BasicConstraints(false)
        );
        certificateBuilder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
        );
        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        return new JcaX509CertificateConverter()
                .getCertificate(certificateBuilder.build(contentSigner));
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



