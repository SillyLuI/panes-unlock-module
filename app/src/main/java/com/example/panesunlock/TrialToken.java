package com.example.panesunlock;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 复刻格窗工作台内部的试用令牌生成算法（见 yf.g / re.E）。
 *
 * 令牌格式： v2;start=<毫秒>;seen=<毫秒>[;hinted];s=<签名>
 * 签名     ： HmacSHA256("panes-trial-seal-v1|f3a9c1d27e", body)
 *             -> URL-safe Base64（去 padding） -> 取前 16 字符
 *
 * 该签名密钥硬编码在 App 内，因此可离线复现；令牌不绑定机器码，全设备通用。
 * seen 设为 2100-01-01，即“永久试用”。
 */
public final class TrialToken {

    private static final String KEY = "panes-trial-seal-v1|f3a9c1d27e";
    private static final long SEEN_FOREVER = 4102444800000L; // 2100-01-01

    private TrialToken() {}

    public static String generate() {
        long start = System.currentTimeMillis();
        String body = "v2;start=" + start + ";seen=" + SEEN_FOREVER + ";hinted";
        return body + ";s=" + sign(body);
    }

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec sk = new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(sk);
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String b64 = Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return b64.length() > 16 ? b64.substring(0, 16) : b64;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return "";
        }
    }
}
