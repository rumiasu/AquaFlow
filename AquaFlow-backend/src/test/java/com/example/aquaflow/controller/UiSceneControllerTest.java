package com.example.aquaflow.controller;

import com.example.aquaflow.util.CosUtil;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UiSceneControllerTest {
    @Test void endpointDoesNotAcceptAnObjectName() throws Exception {
        var method = UiSceneController.class.getMethod("scene");
        assertEquals(0, method.getParameterCount());
        assertArrayEquals(new String[]{"/api/ui-assets/scene-home-water"}, method.getAnnotation(GetMapping.class).value());
    }
    @Test void signsOnlyTheFixedDecorativeAssetAndUsesConservativeExpiry() {
        CosUtil cos = mock(CosUtil.class);
        when(cos.isCredentialsConfigured()).thenReturn(true);
        when(cos.generatePublicUrl("public/ui/scene-home-water.webp")).thenReturn("https://example.invalid/signed");
        long before = System.currentTimeMillis();
        var result = new UiSceneController(cos).scene();
        assertEquals(0, result.getCode());
        assertEquals("https://example.invalid/signed", result.getData().get("url"));
        assertTrue((long) result.getData().get("expiresAt") >= before + 23L * 3600 * 1000);
        verify(cos).isCredentialsConfigured();
        verify(cos).generatePublicUrl("public/ui/scene-home-water.webp");
        verifyNoMoreInteractions(cos);
    }
    @Test void unconfiguredOrFailedSignerDoesNotExposeDetails() {
        CosUtil cos = mock(CosUtil.class);
        assertEquals(1, new UiSceneController(cos).scene().getCode());
        when(cos.isCredentialsConfigured()).thenReturn(true);
        when(cos.generatePublicUrl(anyString())).thenThrow(new IllegalStateException("secret detail"));
        var result = new UiSceneController(cos).scene();
        assertEquals(1, result.getCode());
        assertFalse(result.getMessage().contains("secret"));
    }
}
