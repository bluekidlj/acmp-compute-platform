package com.acmp.compute.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES 加密服务：用于 kubeconfig 等敏感信息的加密存储。
 * 生产环境请使用环境变量 AES_KEY（32 字节），或接入 Vault 等。
 */
@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES";

    private final SecretKeySpec keySpec;

    public EncryptionService(@Value("${encryption.aes-key}") String aesKey) {
        byte[] key = aesKey.getBytes(StandardCharsets.UTF_8);
        if (key.length != 32) {
            throw new IllegalArgumentException("AES key 必须为 32 字节，当前长度: " + key.length);
        }
        this.keySpec = new SecretKeySpec(key, ALGORITHM);
    }

    /** 加密后返回 Base64 字符串，便于存库 */
    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("加密失败", e);
            throw new RuntimeException("加密失败", e);
        }
    }

    /** 从 Base64 密文解密 */
    public String decrypt(String base64Encrypted) {
        if (base64Encrypted == null) return null;
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decoded = Base64.getDecoder().decode(base64Encrypted);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败", e);
            throw new RuntimeException("解密失败", e);
        }
    }
}