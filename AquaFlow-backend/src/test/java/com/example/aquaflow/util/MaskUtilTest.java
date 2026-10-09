package com.example.aquaflow.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class MaskUtilTest {
    private static final String OPENID = "synthetic-openid-private-tail";
    private static final String SESSION = "synthetic-session-private-tail";
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest @ValueSource(strings={"", " ", "\t", "\n", "\r\n", " \t\n"})
    void compactAndWhitespaceJsonCannotRetainSensitiveValues(String ws) throws Exception {
        String response = "{"+ws+"\"openid\""+ws+":"+ws+"\""+OPENID+"\","+ws
                +"\"session_key\""+ws+":"+ws+"\""+SESSION+"\","+ws+"\"errcode\":0}";
        String masked = MaskUtil.maskCode2SessionResponse(response);
        var values = json.readTree(masked);
        assertAll(() -> assertFalse(masked.contains(OPENID)), () -> assertFalse(masked.contains(SESSION)),
                () -> assertEquals(MaskUtil.maskOpenid(OPENID), values.path("openid").asText()),
                () -> assertEquals("***", values.path("session_key").asText()),
                () -> assertEquals(0, values.path("errcode").asInt()));
    }

    @Test void escapedJsonNamesAndValuesAreMaskedAsTheirDecodedFields() throws Exception {
        String response = "{\"\\u006fpenid\":\"synthetic-openid-private-tail\","
                +"\"session_\\u006bey\":\"synthetic-session-private-\\u0074ail\",\"errcode\":0}";
        String masked = MaskUtil.maskCode2SessionResponse(response);
        var fields = json.readTree(masked);
        assertEquals("synt****", fields.path("openid").asText());
        assertEquals("***", fields.path("session_key").asText());
        assertFalse(masked.contains(SESSION));
    }

    @ParameterizedTest @ValueSource(strings={"broken", "array", "scalar"})
    void anUnparseableOrNonObjectResponseIsOmittedRatherThanPrinted(String shape) {
        String response = switch (shape) {
            case "broken" -> "{\"openid\" : \""+OPENID+"\", \"session_key\" : \""+SESSION+"\", bad:"+SESSION+"}";
            case "array" -> "[\""+OPENID+"\",\""+SESSION+"\"]";
            default -> "\""+SESSION+"\"";
        };
        String masked = MaskUtil.maskCode2SessionResponse(response);
        assertAll(() -> assertFalse(masked.contains(OPENID)), () -> assertFalse(masked.contains(SESSION)),
                () -> assertTrue(masked.contains("已省略")));
    }

    @Test void nullAndTheExistingOpenidPrefixPolicyRemainStable() {
        assertEquals("null", MaskUtil.maskCode2SessionResponse(null));
        assertEquals("null", MaskUtil.maskOpenid(null));
        assertEquals("***", MaskUtil.maskOpenid("abcd"));
        assertEquals("synt****", MaskUtil.maskOpenid(OPENID));
    }
}
