package com.example.aquaflow.service;

import com.example.aquaflow.util.JwtUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestRefreshTokenSizeTest {
    @ParameterizedTest @ValueSource(ints={32,48,64,128})
    void testRefreshFits500ForSupportedHmacAlgorithmsNamesAndLongIds(int keyBytes){
        JwtUtil jwt=new JwtUtil();ReflectionTestUtils.setField(jwt,"secret","x".repeat(keyBytes));
        ReflectionTestUtils.setField(jwt,"accessTokenExpiry",3600000L);
        ReflectionTestUtils.setField(jwt,"refreshTokenExpiry",604800000L);
        String binding="ab".repeat(32);
        for(String type:List.of("customer","staff"))for(long id:new long[]{4L,Long.MAX_VALUE})
            for(String name:List.of("test_customer","test_manager","test_delivery","n".repeat(48))){
                String normal=jwt.generateRefreshToken(id,type);
                var before=jwt.parseToken(normal);
                String refresh=jwt.withTestAuthentication(normal,name,binding);
                var claims=jwt.parseToken(refresh);
                assertTrue(refresh.length()<=500,"persisted test refresh must fit VARCHAR(500)");
                assertEquals(JwtUtil.TEST_AUTH_SOURCE,claims.get("authSource"));
                assertFalse(claims.containsKey("testAccount"));
                assertArrayEquals(HexFormat.of().parseHex(binding),Base64.getUrlDecoder().decode(claims.get("testBinding",String.class)));
                assertEquals(before.getId(),claims.getId());assertEquals(before.getSubject(),claims.getSubject());
                assertEquals(before.getIssuedAt(),claims.getIssuedAt());assertEquals(before.getExpiration(),claims.getExpiration());
                assertEquals(id,claims.get("userId",Long.class));assertEquals(type,claims.get("userType"));
                assertNull(before.get("authSource"));assertNull(before.get("testBinding"));
                String access=jwt.withTestAuthentication(jwt.generateAccessToken(id,type,type.equals("staff")?"manager":"customer",5L),name,binding);
                assertEquals(name,jwt.parseToken(access).get("testAccount"));
                assertEquals(binding,jwt.parseToken(access).get("testBinding"));
            }
    }
}
