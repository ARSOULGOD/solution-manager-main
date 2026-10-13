package com.solace.sm.dp.apply.runtime.connection;

import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.apply.runtime.connection.RemoteServerConnectionTester.AuthMethod;
import com.solace.sm.dp.apply.runtime.connection.RemoteServerConnectionTester.Binding;
import com.solace.sm.dp.apply.runtime.connection.RemoteServerConnectionTester.Steps;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.secret.SecretException;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.Resource;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

/**
 * The sshj half of {@code testBeforeSave} for a {@code remote-server} Connection (plan 013 T3, {@code BE-D66}):
 * connect, authenticate with no host-key pinning, disconnect. No command is ever run, unlike
 * {@code SshjTargetSshProbe} — the brief asks for connect-and-authenticate only.
 *
 * <p>{@link PromiscuousVerifier} accepts whatever host key the peer offers — the posture the class javadoc on
 * {@link RemoteServerConnectionTester} names: the same one an {@code ssh} client without
 * {@code StrictHostKeyChecking} has. Nothing here learns, records or compares a host key.
 */
final class SshjConnectionAuthProbe implements RemoteServerConnectionTester.Probe {

    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int OPERATION_TIMEOUT_MILLIS = 15_000;

    private final SecretResolver secrets;
    private final TargetAddressPolicy addressPolicy;

    SshjConnectionAuthProbe(SecretResolver secrets, TargetAddressPolicy addressPolicy) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
    }

    @Override
    public Steps run(Binding binding) {
        // Checked before any session is opened or any credential resolved (fix round 1, owner ruling): the stored
        // host is a name, and the data plane may never connect wherever that name happens to resolve to (SM-D11a
        // P / SSRF). Connecting to the resolved address rather than re-resolving the name keeps "resolve once,
        // connect to what you checked" — the same discipline SshjDockerTransport.checkedAddress/SshjTargetSshProbe
        // follow for Target registration, at the same bar; the two differ only in that this probe pins no host key.
        InetAddress address;
        try {
            address = addressPolicy.connectable(binding.host());
        } catch (TargetAddressRefusedException refused) {
            return new Steps(false, false, "host address refused");
        } catch (UnknownHostException unresolvable) {
            return new Steps(false, false, "host does not resolve");
        }
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        try {
            ssh.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            ssh.setTimeout(OPERATION_TIMEOUT_MILLIS);
            try {
                ssh.connect(address, binding.port());
            } catch (IOException e) {
                return new Steps(false, false, e.getMessage());
            }
            return authenticate(ssh, binding);
        } finally {
            closeQuietly(ssh);
        }
    }

    private Steps authenticate(SSHClient ssh, Binding binding) {
        try {
            if (binding.authMethod() == AuthMethod.BASIC) {
                authenticateWithPassword(ssh, binding);
            } else {
                authenticateWithPrivateKey(ssh, binding);
            }
            return new Steps(true, true, null);
        } catch (SecretException e) {
            // The credential itself is unavailable: a result, not an exception, and the host is never asked to
            // authenticate half a credential.
            return new Steps(true, false, "connection credential unavailable");
        } catch (IOException e) {
            // The host's own rejection reason (brief: "surfaces the broker's own rejection reason on failure" —
            // the same contract applies to the host here). sshj's auth-failure messages name what was refused
            // (e.g. "Exhausted available authentication methods"), never the secret that was tried.
            return new Steps(true, false, e.getMessage());
        }
    }

    private void authenticateWithPassword(SSHClient ssh, Binding binding) throws IOException {
        char[] password = new char[0];
        try {
            if (binding.passwordSecretRef() != null) {
                try (SecretMaterial material = resolve(binding, binding.passwordSecretRef())) {
                    password = material.chars();
                }
            }
            ssh.authPassword(binding.username(), password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void authenticateWithPrivateKey(SSHClient ssh, Binding binding) throws IOException {
        char[] passphrase = null;
        try {
            if (binding.passphraseSecretRef() != null) {
                try (SecretMaterial material = resolve(binding, binding.passphraseSecretRef())) {
                    passphrase = material.chars();
                }
            }
            KeyProvider key;
            try (SecretMaterial material = resolve(binding, binding.privateKeySecretRef())) {
                key = ssh.loadKeys(new String(material.bytes(), StandardCharsets.UTF_8), null,
                        passphrase == null ? null : fixed(passphrase));
            }
            ssh.authPublickey(binding.username(), key);
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    private SecretMaterial resolve(Binding binding, java.util.UUID secretRef) {
        return secrets.resolve(binding.tenant().value(), secretRef, SecretPurpose.CONNECTION_CREDENTIAL);
    }

    private static PasswordFinder fixed(char[] passphrase) {
        return new PasswordFinder() {
            @Override
            public char[] reqPassword(Resource<?> resource) {
                return passphrase;
            }

            @Override
            public boolean shouldRetry(Resource<?> resource) {
                return false;
            }
        };
    }

    private static void closeQuietly(SSHClient ssh) {
        try {
            ssh.close();
        } catch (IOException | RuntimeException ignored) {
            // an attempt that did not connect, or a connection already gone
        }
    }
}
