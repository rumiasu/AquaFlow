package com.example.aquaflow.controller;

import com.example.aquaflow.entity.FileInfo;
import com.example.aquaflow.mapper.FileInfoMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Ownership must be established before either destructive dependency is invoked. */
class FileManageControllerOwnershipTest {
    @AfterEach void clear(){AuthContext.clear();}
    @Test void missingOrForeignOwnershipCannotCallCosOrDelete() {
        Long[][] cases={{null,null,10L},{null,99L,10L},{10L,null,10L},{11L,7L,10L},{null,null,null}};
        for(Long[] data:cases) {
            var mapper=mock(FileInfoMapper.class);var cos=mock(CosUtil.class);var controller=controller(mapper,cos);
            FileInfo row=row(data[0],data[1]);when(mapper.getById(34L)).thenReturn(row);when(mapper.deleteById(34L)).thenReturn(1);
            AuthContext.set(new AuthContext.AuthUser(7L,"staff","STATION_MANAGER",data[2]));
            assertEquals(1,controller.delete(34L).getCode(),java.util.Arrays.toString(data));
            verify(cos,never()).delete(anyString());verify(mapper,never()).deleteById(anyLong());
        }
    }
    @Test void explicitOwnStationAndLegacyPlatformOwnerKeepTheirDeletionRights() {
        for(Long station:new Long[]{10L,null}) {
            var mapper=mock(FileInfoMapper.class);var cos=mock(CosUtil.class);var controller=controller(mapper,cos);
            when(mapper.getById(34L)).thenReturn(row(station,7L));when(mapper.deleteById(34L)).thenReturn(1);
            AuthContext.set(new AuthContext.AuthUser(7L,"staff","STATION_MANAGER",10L));
            assertEquals(0,controller.delete(34L).getCode());verify(cos).delete("public/general/synthetic.pdf");verify(mapper).deleteById(34L);
        }
    }
    @Test void nonexistentFileDoesNotReachDestructiveDependencies() {
        var mapper=mock(FileInfoMapper.class);var cos=mock(CosUtil.class);
        AuthContext.set(new AuthContext.AuthUser(7L,"staff","STATION_MANAGER",10L));
        assertEquals(1,controller(mapper,cos).delete(34L).getCode());verifyNoInteractions(cos);verify(mapper,never()).deleteById(anyLong());
    }
    FileManageController controller(FileInfoMapper mapper,CosUtil cos) {
        var controller=new FileManageController();ReflectionTestUtils.setField(controller,"fileInfoMapper",mapper);ReflectionTestUtils.setField(controller,"cosUtil",cos);return controller;
    }
    FileInfo row(Long station,Long owner){var row=new FileInfo();row.setId(34);row.setStationId(station);row.setUploaderId(owner==null?null:owner.intValue());row.setObjectName("public/general/synthetic.pdf");return row;}
}
