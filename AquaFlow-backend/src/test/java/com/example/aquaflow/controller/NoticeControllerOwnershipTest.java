package com.example.aquaflow.controller;

import com.example.aquaflow.entity.Notice;
import com.example.aquaflow.mapper.NoticeMapper;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Zero mapper writes at the controller boundary; real HTTP/AOP is checked by the notice integration tests. */
class NoticeControllerOwnershipTest {
    @AfterEach void clearIdentity() { AuthContext.clear(); }

    @ParameterizedTest
    @CsvSource({"11, update", "11, delete", "22, update", "22, delete"})
    void aSystemNoticeIsNeverWritableByAStationManager(long stationId, String operation) {
        AuthContext.set(new AuthContext.AuthUser(7L, "staff", "STATION_MANAGER", stationId));
        NoticeMapper mapper = mock(NoticeMapper.class);
        Notice system = new Notice(); system.setId(101L); system.setStationId(null);
        system.setTitle("Synthetic system announcement"); system.setType(1);
        when(mapper.getById(101L)).thenReturn(system);
        NoticeController controller = new NoticeController();
        ReflectionTestUtils.setField(controller, "noticeMapper", mapper);
        Notice edit = new Notice(); edit.setTitle("Unowned edit"); // No request stationId.
        var result = operation.equals("update") ? controller.update(101L, edit) : controller.delete(101L);
        assertAll("null stationId is a system announcement, not a writable station record",
                () -> assertEquals(1, result.getCode()),
                () -> verify(mapper, never()).update(any()),
                () -> verify(mapper, never()).delete(anyLong()));
    }
}
