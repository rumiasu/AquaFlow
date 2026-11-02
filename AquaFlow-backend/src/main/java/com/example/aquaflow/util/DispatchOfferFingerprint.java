package com.example.aquaflow.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** B-03: a stable consent fingerprint, bound to the accepting station and the offer's round. */
public final class DispatchOfferFingerprint {
    private DispatchOfferFingerprint() {}
    public static String token(Map<String,Object> row, Long acceptingStation) {
        if (acceptingStation == null || !(row.get("offer_version") instanceof Number version) || version.longValue() < 1) return null;
        List<String> values = new ArrayList<>();
        for (String key : List.of("order_id","source_station_id","target_station_id","offer_version","service_amount","net_barrels","barrel_amount"))
            values.add(number(row.get(key)));
        values.add(String.valueOf(acceptingStation));
        values.add(text(row.get("barrel_mode")));
        // Stored lines are a set of product/quantity terms; display ordering is not a financial change.
        String items = text(row.get("barrel_items"));
        if (items != null) { List<String> lines = new ArrayList<>(Arrays.asList(items.split("；",-1))); Collections.sort(lines); items = String.join("；",lines); }
        values.add(items); values.add(text(row.get("note")));
        StringBuilder canonical = new StringBuilder("dispatch-offer-v1:");
        for (String value : values) canonical.append(value == null ? "-1:" : value.length()+":"+value);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
    public static boolean matches(String supplied, String current) {
        return supplied != null && current != null && supplied.matches("[a-f0-9]{64}")
                && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),current.getBytes(StandardCharsets.US_ASCII));
    }
    private static String number(Object value) { return value == null ? null : new BigDecimal(value.toString()).stripTrailingZeros().toPlainString(); }
    private static String text(Object value) { return value == null ? null : value.toString(); }
}
