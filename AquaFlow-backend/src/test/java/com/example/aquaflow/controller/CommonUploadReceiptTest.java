package com.example.aquaflow.controller;

import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
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
        when(cos.isCredentialsConfigured()).thenReturn(true);
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
                .andExpect(jsonPath("$.uploadOutcome").value("stored"))
                .andExpect(jsonPath("$.previewUnavailable").value(false))
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

    @Test
    void confirmedPutKeepsKeyWhenPreviewSigningFails() throws Exception {
        CosUtil cos = mock(CosUtil.class);
        when(cos.isCredentialsConfigured()).thenReturn(true);
        String key = "public/common/synthetic-product.png";
        when(cos.uploadPublic(any(byte[].class), anyString(), anyString())).thenReturn(key);
        when(cos.generatePublicUrl(key)).thenThrow(new IllegalStateException("synthetic signer failure"));
        CommonController controller = new CommonController();
        ReflectionTestUtils.setField(controller, "cosUtil", cos);
        AuthContext.set(new AuthContext.AuthUser(1L, "staff", "STATION_MANAGER", 11L));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(multipart("/api/common/upload").file(new MockMultipartFile("file", "synthetic.png", "image/png", new byte[]{1})))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.objectName").value(key))
                .andExpect(jsonPath("$.uploadOutcome").value("stored"))
                .andExpect(jsonPath("$.previewUnavailable").value(true))
                .andExpect(jsonPath("$.uploadDiagnosticCode").value("UPLOAD_PREVIEW_UNAVAILABLE"));
        verify(cos, times(1)).uploadPublic(any(byte[].class), anyString(), anyString());
    }

    @Test
    void missingConfigurationIsExplicitlyRejectedBeforePut() throws Exception {
        CosUtil cos = mock(CosUtil.class);
        CommonController controller = new CommonController();
        ReflectionTestUtils.setField(controller, "cosUtil", cos);
        AuthContext.set(new AuthContext.AuthUser(1L, "staff", "STATION_MANAGER", 11L));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(multipart("/api/common/upload").file(new MockMultipartFile("file", "synthetic.png", "image/png", new byte[]{1})))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.uploadOutcome").value("rejected"))
                .andExpect(jsonPath("$.uploadDiagnosticCode").value("UPLOAD_STORAGE_UNAVAILABLE"));
        verify(cos, never()).uploadPublic(any(byte[].class), anyString(), anyString());
    }

    @Test
    void storageExceptionRemainsUnknownRatherThanClaimingNoObjectWasWritten() throws Exception {
        CosUtil cos = mock(CosUtil.class);
        when(cos.isCredentialsConfigured()).thenReturn(true);
        when(cos.uploadPublic(any(byte[].class), anyString(), anyString())).thenThrow(new IllegalStateException("synthetic timeout"));
        CommonController controller = new CommonController();
        ReflectionTestUtils.setField(controller, "cosUtil", cos);
        AuthContext.set(new AuthContext.AuthUser(1L, "staff", "STATION_MANAGER", 11L));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(multipart("/api/common/upload").file(new MockMultipartFile("file", "synthetic.png", "image/png", new byte[]{1})))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.uploadOutcome").value("unknown"))
                .andExpect(jsonPath("$.uploadDiagnosticCode").value("UPLOAD_STORAGE_UNCONFIRMED"))
                .andExpect(jsonPath("$.objectName").doesNotExist());
        verify(cos, never()).generatePublicUrl(anyString());
    }

    @Test
    void storageServiceRejectionAndClientTimeoutHaveTheSamePublicCodeButNeverReachPreviewSigning() throws Exception {
        CosServiceException denied = new CosServiceException("synthetic service rejection");
        denied.setStatusCode(403);
        denied.setErrorCode("AccessDenied");
        denied.setRequestId("synthetic-request-id");
        CosClientException timeout = new CosClientException("synthetic client timeout",
                new java.net.SocketTimeoutException("synthetic timeout"));
        for (RuntimeException failure : new RuntimeException[] { denied, timeout }) {
            CosUtil cos = mock(CosUtil.class);
            when(cos.isCredentialsConfigured()).thenReturn(true);
            when(cos.uploadPublic(any(byte[].class), anyString(), anyString())).thenThrow(failure);
            CommonController controller = new CommonController();
            ReflectionTestUtils.setField(controller, "cosUtil", cos);
            AuthContext.set(new AuthContext.AuthUser(1L, "staff", "STATION_MANAGER", 11L));
            MockMvcBuilders.standaloneSetup(controller).build()
                    .perform(multipart("/api/common/upload").file(new MockMultipartFile("file", "synthetic.png", "image/png", new byte[]{1})))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.message").value("图片上传暂未确认，请稍后查看"))
                    .andExpect(jsonPath("$.uploadOutcome").value("unknown"))
                    .andExpect(jsonPath("$.uploadDiagnosticCode").value("UPLOAD_STORAGE_UNCONFIRMED"))
                    .andExpect(jsonPath("$.objectName").doesNotExist());
            verify(cos, never()).generatePublicUrl(anyString());
        }
    }
}
