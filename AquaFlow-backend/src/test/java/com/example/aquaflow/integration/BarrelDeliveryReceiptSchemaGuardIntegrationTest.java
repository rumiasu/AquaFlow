package com.example.aquaflow.integration;

import com.example.aquaflow.config.BarrelDeliveryReceiptSchemaGuard;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

import static org.junit.jupiter.api.Assertions.*;

/** 在已确认的隔离测试库中验证实际元数据；每次临时 DDL 均恢复，绝不修复业务库结构。 */
class BarrelDeliveryReceiptSchemaGuardIntegrationTest extends AbstractIntegrationTest {
    private BarrelDeliveryReceiptSchemaGuard guard() {
        return new BarrelDeliveryReceiptSchemaGuard(jdbc);
    }

    @Test
    void migrationShapeIsAcceptedWithoutWritingReceiptEvidence() {
        assertDoesNotThrow(() -> guard().verifySchema());
        assertEquals(0, intOf("select count(*) from barrel_delivery_receipt"));
        assertEquals(0, intOf("select count(*) from barrel_return_delivery_allocation"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"barrel_delivery_receipt", "barrel_return_delivery_allocation"})
    void missingTimestampDefaultIsRejectedBeforeItsNormalInsertCanFail(String table) {
        jdbc.execute("alter table " + table + " alter column create_time drop default");
        try {
            String insert = "barrel_delivery_receipt".equals(table)
                    ? "insert into barrel_delivery_receipt(record_id,input_kind) values(1,'ITEMIZED')"
                    : "insert into barrel_return_delivery_allocation(return_record_id,delivery_record_id,quantity,operator_id) values(2,1,1,1)";
            assertThrows(DataAccessException.class, () -> jdbc.update(insert), "业务写入省略 create_time，错误结构会让整笔业务回滚");
            assertThrows(IllegalStateException.class, () -> guard().verifySchema(), "不能将会拒绝正常实收写入的结构判为可用");
        } finally {
            jdbc.execute("alter table " + table + " modify create_time datetime not null default current_timestamp");
        }
        assertDoesNotThrow(() -> guard().verifySchema());
    }

    @ParameterizedTest
    @ValueSource(strings = {"barrel_delivery_receipt", "barrel_return_delivery_allocation"})
    void fixedTimestampDefaultCannotPretendToBeTheReceiptCreationTime(String table) {
        jdbc.execute("alter table " + table + " alter column create_time set default '2000-01-01 00:00:00'");
        try {
            assertThrows(IllegalStateException.class, () -> guard().verifySchema(), "凭据时间必须来自当前写入，不能接受同类型固定默认值");
        } finally {
            jdbc.execute("alter table " + table + " modify create_time datetime not null default current_timestamp");
        }
        assertDoesNotThrow(() -> guard().verifySchema());
    }

    @Test
    void undersizedInputKindIsRejectedBeforeNormalDeliveryCanFail() {
        jdbc.execute("alter table barrel_delivery_receipt modify input_kind varchar(1) character set ascii collate ascii_bin not null");
        try {
            assertThrows(DataAccessException.class, () -> jdbc.update(
                    "insert into barrel_delivery_receipt(record_id,input_kind) values(1,'ITEMIZED')"));
            assertThrows(IllegalStateException.class, () -> guard().verifySchema(), "VARCHAR 类型相同不能证明容得下实际来源值");
        } finally {
            jdbc.execute("alter table barrel_delivery_receipt modify input_kind varchar(32) character set ascii collate ascii_bin not null");
        }
        assertDoesNotThrow(() -> guard().verifySchema());
    }
}
