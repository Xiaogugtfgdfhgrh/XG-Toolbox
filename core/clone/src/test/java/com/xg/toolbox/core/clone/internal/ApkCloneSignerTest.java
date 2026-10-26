package com.xg.toolbox.core.clone.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import org.junit.Test;

public class ApkCloneSignerTest {
    @Test
    public void certificateCreationUsesPlatformRsaWithoutBouncyCastleProvider() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        X509Certificate certificate = ApkCloneSigner.createSelfSignedCertificate(
                keyPair,
                new SecureRandom()
        );

        assertNotNull(certificate);
        assertEquals("SHA256withRSA", certificate.getSigAlgName());
        assertTrue(certificate.getSubjectX500Principal().getName().contains("CN=XG-SelfClone-"));
        certificate.checkValidity();
        certificate.verify(keyPair.getPublic());
    }
}
