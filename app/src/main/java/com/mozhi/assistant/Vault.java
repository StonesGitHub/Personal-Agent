package com.mozhi.assistant;

import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted payloads; the AES key remains in Android Keystore. */
public final class Vault {
    private final javax.crypto.SecretKey key;
    public Vault() throws Exception {key=loadKey();}
    private static synchronized javax.crypto.SecretKey loadKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        String alias="mozhi.local.v1";
        if(!ks.containsAlias(alias)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        java.security.Key found=ks.getKey(alias,null);
        if(!(found instanceof javax.crypto.SecretKey))throw new java.security.KeyStoreException("设备密钥尚未就绪");
        return (javax.crypto.SecretKey)found;
    }
    public String encrypt(String plain) {
        try {
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);
            return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        } catch(Exception e) { throw new IllegalStateException("本机加密失败",e); }
    }
    public String decrypt(String encoded) {
        try {
            String[] parts=encoded.split(":",2);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("本机资料无法解密；请勿清除应用数据，先保留原设备。",e); }
    }
}
