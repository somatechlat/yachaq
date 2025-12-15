package com.yachaq.blockchain.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for blockchain connectivity.
 */
@Configuration
@ConfigurationProperties(prefix = "yachaq.blockchain")
public class BlockchainConfig {

    private String nodeUrl = "http://localhost:8545";
    private String escrowContractAddress;
    private String consentRegistryAddress;
    private String auditAnchorAddress;
    private String privateKey;
    private long gasPrice = 20_000_000_000L; // 20 Gwei
    private long gasLimit = 6_721_975L;
    private boolean enabled = false;

    public String getNodeUrl() { return nodeUrl; }
    public void setNodeUrl(String nodeUrl) { this.nodeUrl = nodeUrl; }
    public String getEscrowContractAddress() { return escrowContractAddress; }
    public void setEscrowContractAddress(String addr) { this.escrowContractAddress = addr; }
    public String getConsentRegistryAddress() { return consentRegistryAddress; }
    public void setConsentRegistryAddress(String addr) { this.consentRegistryAddress = addr; }
    public String getAuditAnchorAddress() { return auditAnchorAddress; }
    public void setAuditAnchorAddress(String addr) { this.auditAnchorAddress = addr; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    public long getGasPrice() { return gasPrice; }
    public void setGasPrice(long gasPrice) { this.gasPrice = gasPrice; }
    public long getGasLimit() { return gasLimit; }
    public void setGasLimit(long gasLimit) { this.gasLimit = gasLimit; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
