package ra.i2p;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the {@code copyCertificatesToBaseDir}/{@code copyResourceCertificate}
 * fix (2026-09-20): the previous implementation branched on packaging shape
 * ({@code getProtectionDomain().getCodeSource().getLocation()} inspected as a {@link File}), and
 * its fallback threw {@code IllegalArgumentException: URI is not hierarchical} when this class
 * was loaded from a jar nested inside another jar (a Spring Boot repackaged fat jar) - confirmed
 * by actually running a real 1m5-nostr-node-java deployment image, not assumed. The fix removes
 * that branching entirely in favor of {@code getResourceAsStream} everywhere, which is exactly
 * what this test exercises via Maven Surefire's own classpath (classes on disk, not a jar at
 * all) - the fix's whole point is that the code path is now identical regardless of how this
 * class ends up packaged, so there is nothing further to vary here to prove that.
 */
public class I2PServiceCertificatesTest {

    private static final String[] RESEED_CERT_NAMES = {
            "backup_at_mail.i2p.crt", "bugme_at_mail.i2p.crt", "creativecowpat_at_mail.i2p.crt",
            "echelon_at_mail.i2p.crt", "hottuna_at_mail.i2p.crt", "igor_at_novg.net.crt",
            "lazygravy_at_mail.i2p.crt", "meeh_at_mail.i2p.crt", "reseedi2pnetin_at_mail.i2p.crt",
    };
    private static final String[] SSL_CERT_NAMES = {
            "echelon.reseed2017.crt", "i2p.mooo.com.crt", "i2pseed.creativecowpat.net.crt",
            "isrgrootx1.crt", "reseed.onion.im.crt",
    };

    @Test
    public void copiesEveryBundledReseedAndSslCertificate() throws IOException {
        File reseedDir = Files.createTempDirectory("i2p-reseed-certs-test").toFile();
        File sslDir = Files.createTempDirectory("i2p-ssl-certs-test").toFile();

        I2PService service = new I2PService();
        assertTrue("copyCertificatesToBaseDir should report success",
                service.copyCertificatesToBaseDir(reseedDir, sslDir));

        for (String name : RESEED_CERT_NAMES) {
            File f = new File(reseedDir, name);
            assertTrue("missing reseed cert: " + name, f.isFile());
            assertTrue("reseed cert is empty: " + name, f.length() > 0);
        }
        for (String name : SSL_CERT_NAMES) {
            File f = new File(sslDir, name);
            assertTrue("missing ssl cert: " + name, f.isFile());
            assertTrue("ssl cert is empty: " + name, f.length() > 0);
        }
    }

    @Test
    public void reCopyingOverAnExistingFileStillSucceeds() throws IOException {
        File dir = Files.createTempDirectory("i2p-cert-recopy-test").toFile();
        I2PService service = new I2PService();

        File dest = new File(dir, "isrgrootx1.crt");
        assertTrue(service.copyResourceCertificate("certificates/ssl/isrgrootx1.crt", dest));
        long firstLength = dest.length();
        // Copying again must not fail just because the destination already exists from the
        // first copy - this is the exact "already deployed once, redeploying" case in production.
        assertTrue(service.copyResourceCertificate("certificates/ssl/isrgrootx1.crt", dest));
        assertEquals(firstLength, dest.length());
    }

    @Test
    public void missingResourceFailsCleanlyRatherThanThrowing() throws IOException {
        File dest = new File(Files.createTempDirectory("i2p-cert-missing-test").toFile(), "nope.crt");
        I2PService service = new I2PService();

        assertFalse(service.copyResourceCertificate("certificates/ssl/does-not-exist.crt", dest));
        assertFalse(dest.exists());
    }
}