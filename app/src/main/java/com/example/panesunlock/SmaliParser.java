package com.example.panesunlock;

import java.util.ArrayList;
import java.util.List;

/**
 * smali 片段解析：从粘贴的 smali（至少含 .method 行，最好含 .class 行）
 * 提取 类名 / 方法名 / 参数类型 / 返回类型，并按返回类型猜测默认修改值。
 * 示例输入：
 *   .class public Lcom/example/Example;
 *   .method public isVip(ZILjava/lang/String;)Z
 */
public class SmaliParser {

    public static class Result {
        public boolean ok;
        public String error = "";
        public String className = "";
        public String methodName = "";
        public String paramTypes = "";
        public String returnType = "";
        public String guessedValue = "";
    }

    public static Result parse(String smali) {
        Result r = new Result();
        if (smali == null || smali.trim().isEmpty()) {
            r.error = "内容为空";
            return r;
        }
        String[] lines = smali.split("\\r?\\n");

        // .class 行 → 类名
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith(".class")) {
                String cls = extractClass(t);
                if (!cls.isEmpty()) {
                    r.className = cls;
                    break;
                }
            }
        }

        // .method 行 → 方法签名
        for (String line : lines) {
            String t = line.trim();
            if (!t.startsWith(".method")) continue;
            String sig = null;
            for (String tok : t.split("\\s+")) {
                if (tok.contains("(") && tok.contains(")") && !tok.startsWith(".method")) {
                    sig = tok;
                    break;
                }
            }
            if (sig == null) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(.+)\\((.*)\\)(.+)$").matcher(sig);
            if (!m.find()) continue;
            r.methodName = m.group(1).trim();
            try {
                List<String> pts = parseDescriptors(m.group(2));
                r.paramTypes = join(pts);
                r.returnType = toJavaType(m.group(3).trim());
            } catch (Throwable e) {
                r.error = "类型描述符解析失败：" + e.getMessage();
                return r;
            }
            r.guessedValue = guessValue(r.returnType);
            r.ok = true;
            return r;
        }
        r.error = "未找到 .method 行";
        return r;
    }

    /** ".class public final Lcom/a/b; implements ..." → com.a.b */
    private static String extractClass(String classLine) {
        for (String tok : classLine.split("\\s+")) {
            if (tok.startsWith("L") && tok.endsWith(";")) {
                return toJavaType(tok);
            }
        }
        return "";
    }

    /** 顺序解析 smali 参数描述符串，如 "ZILjava/lang/String;[B" */
    private static List<String> parseDescriptors(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int arrays = 0;
            while (i < s.length() && s.charAt(i) == '[') {
                arrays++;
                i++;
            }
            if (i >= s.length()) break;
            char c = s.charAt(i);
            if (c == 'L') {
                int semi = s.indexOf(';', i);
                if (semi < 0) throw new IllegalArgumentException("缺少 ';'：" + s.substring(i));
                out.add(withArrays(s.substring(i, semi + 1), arrays));
                i = semi + 1;
            } else {
                out.add(withArrays(String.valueOf(c), arrays));
                i++;
            }
        }
        return out;
    }

    private static String withArrays(String desc, int arrays) {
        String t = toJavaType(desc);
        StringBuilder sb = new StringBuilder(t);
        for (int k = 0; k < arrays; k++) sb.append("[]");
        return sb.toString();
    }

    /** smali 类型描述符 → Java 类型名 */
    public static String toJavaType(String desc) {
        if (desc == null || desc.isEmpty()) return "";
        switch (desc) {
            case "Z": return "boolean";
            case "B": return "byte";
            case "S": return "short";
            case "C": return "char";
            case "I": return "int";
            case "J": return "long";
            case "F": return "float";
            case "D": return "double";
            case "V": return "void";
            default:
                if (desc.startsWith("L") && desc.endsWith(";")) {
                    return desc.substring(1, desc.length() - 1).replace('/', '.');
                }
                return desc.replace('/', '.');
        }
    }

    /** 按返回类型猜一个合理的默认修改值 */
    public static String guessValue(String returnType) {
        if (returnType == null) return "";
        switch (returnType) {
            case "boolean": return "true";
            case "int": return "0";
            case "long": return "0L";
            case "float": return "0.0f";
            case "double": return "0.0d";
            case "short": return "0short";
            case "byte": return "0b";
            default: return "";
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
