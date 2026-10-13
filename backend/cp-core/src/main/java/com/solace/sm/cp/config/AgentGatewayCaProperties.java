package com.solace.sm.cp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The operator-supplied CA the control plane signs agent certificates with (plan 015 T2.2, {@code OR-126} A4). SM
 * generates no CA key: the operator provides a keystore, and without one nothing is ever signed. {@code required}
 * turns that into a refusal to START (of the CA, and of the lease-nonce HMAC key, a secret-key entry under
 * {@code leaseNonceKeyAlias} in the same keystore and read with the same password) (the launcher's agent composition sets it); left false the process still refuses
 * every issuance, only later.
 */
@ConfigurationProperties(prefix = "sm.control.agent-gateway.ca")
public class AgentGatewayCaProperties {
    private String keyStoreFile = "";
    private String keyStoreType = "PKCS12";
    private String keyStorePassword = "";
    private String keyAlias = "";
    private Duration maximumLeafLifetime = Duration.ofDays(30);
    private String connectEndpoint = "";
    private String leaseNonceKeyAlias = "lease-nonce-hmac";
    private boolean required;

    public String getKeyStoreFile() { return keyStoreFile; }
    public void setKeyStoreFile(String keyStoreFile) { this.keyStoreFile = keyStoreFile; }
    public String getKeyStoreType() { return keyStoreType; }
    public void setKeyStoreType(String keyStoreType) { this.keyStoreType = keyStoreType; }
    public String getKeyStorePassword() { return keyStorePassword; }
    public void setKeyStorePassword(String keyStorePassword) { this.keyStorePassword = keyStorePassword; }
    public String getKeyAlias() { return keyAlias; }
    public void setKeyAlias(String keyAlias) { this.keyAlias = keyAlias; }
    public Duration getMaximumLeafLifetime() { return maximumLeafLifetime; }
    public void setMaximumLeafLifetime(Duration maximumLeafLifetime) { this.maximumLeafLifetime = maximumLeafLifetime; }
    public String getConnectEndpoint() { return connectEndpoint; }
    public void setConnectEndpoint(String connectEndpoint) { this.connectEndpoint = connectEndpoint; }
    public String getLeaseNonceKeyAlias() { return leaseNonceKeyAlias; }
    public void setLeaseNonceKeyAlias(String leaseNonceKeyAlias) { this.leaseNonceKeyAlias = leaseNonceKeyAlias; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
}
