package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 015 T2.2 Step 4 (P15-13, OR-127 L2): the launcher resolves exactly one version of each Bouncy Castle artifact, 1.86.
 * Revision 1 of the plan said no other would resolve; in fact {@code dp-core} resolved bcpkix 1.80 and bcutil 1.80.2
 * through {@code sshj} beside cp-core's bcprov 1.86. The pin lives in {@code backend/pom.xml} {@code dependencyManagement}.
 * The jar a class is loaded from is the resolved artifact, so this reads it from the classpath rather than from a pom.
 */
class BouncyCastleSingleVersionTest {

    @Test
    void bcprovBcpkixAndBcutilAreAllTheOneApprovedVersion() throws Exception {
        // One class from each of the three artifacts. bcutil's org.bouncycastle.asn1.bsi lives only in bcutil.
        List<Class<?>> classes = List.of(org.bouncycastle.jce.provider.BouncyCastleProvider.class,
                org.bouncycastle.cert.X509CertificateHolder.class, org.bouncycastle.asn1.bsi.BSIObjectIdentifiers.class);
        List<String> names = List.of("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on");
        for (int i = 0; i < classes.size(); i++) {
            assertEquals(names.get(i) + "-1.86.jar", jarOf(classes.get(i)).getFileName().toString(),
                    classes.get(i).getName() + " must come from the single approved version");
        }
    }

    private static Path jarOf(Class<?> type) throws URISyntaxException {
        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
