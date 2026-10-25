package com.example.aquaflow.util;

import com.example.aquaflow.exception.BusinessException;
import java.net.URI;
import java.util.Locale;

/** Persistent station image references only; null request means keep, empty means restore. */
public final class StationProductImage {
    private StationProductImage() {}
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > 512 || value.contains("?") || value.contains("#") || value.contains("\\")
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        if (value.startsWith("https://")) {
            try {
                URI uri = URI.create(value);
                if (invalidHost(uri.getHost()) || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                        || uri.getPath() == null || !uri.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif|avif)")) throw invalid();
            } catch (IllegalArgumentException e) { throw invalid(); }
            return value;
        }
        if (value.contains(":") || value.startsWith("//") || value.startsWith("/")
                && !(value.startsWith("/assets/") || value.startsWith("/images/"))) throw invalid();
        for (String part : value.split("/")) if (part.equals("..") || part.equals(".")) throw invalid();
        if (!value.matches("[\\p{L}\\p{N}._/\\-]+")) throw invalid();
        if (!(value.startsWith("public/") || value.startsWith("/assets/") || value.startsWith("/images/")
                || value.matches("catalog-sample-[A-Za-z0-9._-]+\\.(png|jpe?g|webp)"))) throw invalid();
        if (!value.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif|avif)")) throw invalid();
        return value;
    }
    private static boolean invalidHost(String host) {
        if (host == null) return true;
        String h = host.toLowerCase(Locale.ROOT);
        return !h.contains(".") || h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
                || h.startsWith("127.") || h.startsWith("0.") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*") || h.contains(":") || h.startsWith("169.254.");
    }
    private static BusinessException invalid() { return new BusinessException("商品图片尚未取得有效保存凭据，请重新上传或从图库选择"); }
}
