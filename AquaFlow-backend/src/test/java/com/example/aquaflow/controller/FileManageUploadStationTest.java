package com.example.aquaflow.controller;

import com.example.aquaflow.mapper.FileInfoMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual controller with synthetic storage/mapper boundaries; does not contact COS or a database. */
class FileManageUploadStationTest {
    private final CosUtil storage = mock(CosUtil.class);
    private final FileInfoMapper mapper = mock(FileInfoMapper.class);
    private final FileManageController controller = new FileManageController();

    FileManageUploadStationTest() {
        ReflectionTestUtils.setField(controller, "cosUtil", storage);
        ReflectionTestUtils.setField(controller, "fileInfoMapper", mapper);
        when(storage.uploadFailureMessage()).thenReturn("Synthetic upload failure");
    }
    @AfterEach void clearIdentity() { AuthContext.clear(); }
    private MockMultipartFile file() { return new MockMultipartFile("file", "synthetic.png", "image/png", new byte[]{1, 2, 3}); }

    @Test void unboundManagerCannotCreateAnObjectBeforeStationValidation() {
        AuthContext.set(new AuthContext.AuthUser(7L, "staff", "STATION_MANAGER", null));
        var result = controller.upload(file(), "general");
        assertEquals(1, result.getCode()); assertEquals("Synthetic upload failure", result.getMessage());
        verify(storage, never()).uploadPublic(any(), anyString(), anyString());
        verifyNoInteractions(mapper);
    }
    @Test void boundManagerPersistsTheValidatedStationAndKeepsNormalUploadResponse() {
        AuthContext.set(new AuthContext.AuthUser(7L, "staff", "STATION_MANAGER", 11L));
        when(storage.uploadPublic(any(), eq("public/general"), eq(".png"))).thenReturn("public/general/synthetic.png");
        when(storage.generatePublicUrl("public/general/synthetic.png")).thenReturn("synthetic-url");
        var result = controller.upload(file(), "general");
        assertEquals(0, result.getCode()); assertEquals(11L, result.getData().getStationId());
        assertEquals(7, result.getData().getUploaderId()); assertEquals("synthetic-url", result.getData().getUrl());
        verify(mapper).insert(argThat(f -> f.getStationId().equals(11L) && f.getCategory().equals("general")));
    }
    @Test void storageFailureStillUsesTheExistingBusinessErrorEnvelope() {
        AuthContext.set(new AuthContext.AuthUser(7L, "staff", "STATION_MANAGER", 11L));
        when(storage.uploadPublic(any(), anyString(), anyString())).thenThrow(new IllegalStateException("Synthetic storage outage"));
        var result = controller.upload(file(), "general");
        assertEquals(1, result.getCode()); assertEquals("Synthetic upload failure", result.getMessage());
        verifyNoInteractions(mapper);
    }
}
