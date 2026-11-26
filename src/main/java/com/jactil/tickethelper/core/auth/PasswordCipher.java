package com.jactil.tickethelper.core.auth;

import com.jactil.tickethelper.core.client.TicketClient;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Base64;

/**
 * 12306 前端的密码加密：SM4-ECB，密钥 tiekeyuankp12306，结果加 @ 前缀。
 *
 * <p>只在短信登录的那一次请求里用，用完即丢，绝不落盘、绝不写日志。
 */
public final class PasswordCipher {

    private static final String SM4_KEY = "tiekeyuankp12306";

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private PasswordCipher() {
    }

    public static String encrypt(String password) {
        try {
            SecretKeySpec key = new SecretKeySpec(SM4_KEY.getBytes(StandardCharsets.UTF_8), "SM4");
            Cipher cipher = Cipher.getInstance("SM4/ECB/PKCS5Padding", "BC");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return "@" + Base64.getEncoder().encodeToString(
                    cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SM4 加密失败", e);
        }
    }

    /** 供单测确认「传出去的确实不是明文」。 */
    static boolean looksEncrypted(String value) {
        return value != null && value.startsWith("@") && value.length() > 8;
    }
}
