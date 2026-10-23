package com.example.aquaflow.controller;

import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC serialization with mocked COS only; no credentials, network or database. */
class CommonUploadReceiptTest {
    @AfterEach
    void clearIdentity() { AuthContext.clear(); }

    @Test
    void uploadKeepsLegacyUrlDataAndSerializesActualObjectKey() throws Exception {
        CosUtil cos = mock(CosUtil.class);
        String key = "public/common/synthetic-product.png";
        String url = "https://synthetic.invalid/public/common/synthetic-product.png?signature=synthetic";
        when(cos.uploadPublic(any(byte[].class), eq("public/common"), eq(".png"))).thenReturn(key);
        when(cos.generatePublicUrl(key)).thenReturn(url);
        CommonController controller = new CommonController();
        ReflectionTestUtils.setField(controller, "cosUtil", cos);
        AuthContext.set(new AuthContext.AuthUser(1L, "staff", "STATION_MANAGER", 11L));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(multipart("/api/common/upload").file(new MockMultipartFile(
                        "file", "synthetic.png", "image/png", new byte[]{1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(url))
                .andExpect(jsonPath("$.objectName").value(key));
        verify(cos).uploadPublic(new byte[]{1, 2, 3}, "public/common", ".png");
        verify(cos).generatePublicUrl(key);
    }

    @Test
    void unauthenticatedUploadStillDoesNotCallStorage() throws Exception {
        CosUtil cos = mock(CosUtil.class);
        CommonController controller = new CommonController();
        ReflectionTestUtils.setField(controller, "cosUtil", cos);
        AuthContext.clear();
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(multipart("/api/common/upload").file(new MockMultipartFile(
                        "file", "synthetic.png", "image/png", new byte[]{1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.objectName").doesNotExist());
        verifyNoInteractions(cos);
    }
}
