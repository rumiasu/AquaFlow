package com.example.aquaflow.controller;

import com.example.aquaflow.entity.Notice;
import com.example.aquaflow.mapper.NoticeMapper;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

    private Notice notice(long station) {
        Notice n = new Notice(); n.setId(101L); n.setStationId(station);
        n.setTitle("Synthetic title"); n.setContent("Synthetic body"); n.setType(2); n.setStatus(0);
        return n;
    }
    private NoticeController controller(NoticeMapper mapper) {
        AuthContext.set(new AuthContext.AuthUser(7L, "staff", "STATION_MANAGER", 11L));
        NoticeController c = new NoticeController(); ReflectionTestUtils.setField(c, "noticeMapper", mapper); return c;
    }
    @Test void missingAtWriteIsAnExplicitError() {
        NoticeMapper mapper = mock(NoticeMapper.class);
        when(mapper.getById(101L)).thenReturn(notice(11), null);
        assertEquals(1, controller(mapper).update(101L, notice(11)).getCode());
        verify(mapper).update(any());
    }
    @Test void sameValueZeroChangedRowsStillMeansSaved() {
        NoticeMapper mapper = mock(NoticeMapper.class);
        when(mapper.getById(101L)).thenReturn(notice(11));
        assertEquals(0, controller(mapper).update(101L, notice(11)).getCode());
        verify(mapper, times(2)).getById(101L);
    }
    @Test void zeroRowsCannotConfirmAnotherStationOrDifferentContent() {
        for (boolean changedOwner : new boolean[]{true, false}) {
            NoticeMapper mapper = mock(NoticeMapper.class); Notice changed = notice(changedOwner ? 22 : 11);
            if (!changedOwner) changed.setTitle("Synthetic changed title");
            when(mapper.getById(101L)).thenReturn(notice(11), changed);
            assertEquals(1, controller(mapper).update(101L, notice(11)).getCode());
        }
    }
    @Test void successfulUpdateUsesTheLoginStationAndPathId() {
        NoticeMapper mapper = mock(NoticeMapper.class); when(mapper.getById(101L)).thenReturn(notice(11));
        when(mapper.update(any())).thenReturn(1);
        Notice request = notice(22); request.setId(999L);
        assertEquals(0, controller(mapper).update(101L, request).getCode());
        verify(mapper).update(argThat(n -> n.getId().equals(101L) && n.getStationId().equals(11L)));
    }
}
