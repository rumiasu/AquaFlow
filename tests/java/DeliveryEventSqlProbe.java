import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.OrderMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.time.LocalDateTime;
import java.util.List;

/** Standalone probe: compile with application/MyBatis classes and run with H2 2.3.232 on the classpath.
 * Executes the production mapper against this JVM's synthetic memory database only; never loads Spring or a datasource config. */
public class DeliveryEventSqlProbe {
    static int passed;
    static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++; System.out.println("PASS " + name);
    }
    static List<Long> ids(List<Orders> rows) { return rows.stream().map(Orders::getId).sorted().toList(); }
    @SuppressWarnings("unchecked")
    static List<Orders> daily(OrderMapper mapper) throws Exception {
        var start = LocalDateTime.parse("2026-10-24T00:00:00");
        try {
            return (List<Orders>) OrderMapper.class.getMethod("listDeliveredByDeliveryStaffIdAndPeriod", Long.class, LocalDateTime.class, LocalDateTime.class)
                    .invoke(mapper, 20L, start, start.plusDays(1));
        } catch (NoSuchMethodException oldMapper) {
            // Run the same assertions against the pre-fix compiled mapper as a negative control.
            return (List<Orders>) OrderMapper.class.getMethod("listByDeliveryStaffIdAndDate", Long.class, Integer.class, java.time.LocalDate.class)
                    .invoke(mapper, 20L, 4, start.toLocalDate());
        }
    }
    public static void main(String[] args) throws Exception {
        var ds = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:delivery_events;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = ds.getConnection(); var sql = connection.createStatement()) {
            for (String ddl : List.of(
                    "create table orders(id bigint primary key,delivery_staff_id bigint,status int,customer_id bigint,address_id bigint,station_id bigint,delivery_station_id bigint,return_bucket_qty int,update_time timestamp)",
                    "create table customer(id bigint primary key,name varchar(80),phone varchar(30))",
                    "create table address(id bigint primary key,detail varchar(80),floor int,has_elevator int)",
                    "create table order_item(id bigint primary key,order_id bigint,product_id bigint,product_name_snapshot varchar(80),quantity int)",
                    "create table product(id bigint primary key,category int)",
                    "create table audit_log(id bigint primary key,module varchar(30),action varchar(30),target varchar(80),create_time timestamp)",
                    "create table barrel_record(id bigint primary key,related_order_id bigint,type int,create_time timestamp)",
                    "create table order_transfer(id bigint primary key,order_id bigint,kind varchar(30),status varchar(30),sub_kind varchar(30))")) sql.execute(ddl);
            // update_time is deliberately today on a yesterday delivery and yesterday on a today delivery.
            for (String insert : List.of(
                    "insert into orders values(1,20,3,7,1,8,8,2,'2026-10-23 09:00:00'),(2,20,4,7,1,8,8,4,'2026-10-24 09:00:00'),(3,20,4,7,1,8,8,0,'2026-10-24 09:00:00'),(4,20,3,7,1,8,8,3,'2026-10-24 09:00:00'),(5,20,4,7,1,8,8,1,'2026-10-24 09:00:00'),(6,20,2,7,1,8,8,9,'2026-10-24 09:00:00'),(7,21,4,7,1,8,8,9,'2026-10-24 09:00:00'),(8,20,5,7,1,8,8,9,'2026-10-24 09:00:00'),(9,20,4,7,1,8,8,1,'2026-10-24 09:00:00'),(10,20,4,7,1,8,8,1,'2026-10-24 09:00:00')",
                    "insert into customer values(7,'profile','profile-phone')",
                    "insert into address values(1,'snapshot',2,0)",
                    "insert into audit_log values(1,'ORDER','COMPLETE','order:1','2026-10-24 00:00:00'),(2,'ORDER','COMPLETE','order:2','2026-10-23 10:00:00'),(3,'ORDER','COMPLETE','order:3','2026-10-24 23:59:59'),(4,'ORDER','COMPLETE','order:5','2026-10-25 00:00:00'),(5,'ORDER','COMPLETE','order:6','2026-10-24 09:00:00'),(6,'ORDER','COMPLETE','order:7','2026-10-24 09:00:00'),(7,'ORDER','COMPLETE','order:8','2026-10-24 09:00:00'),(8,'PAYMENT','COMPLETE','order:9','2026-10-24 09:00:00'),(9,'ORDER','COLLECT','order:10','2026-10-24 09:00:00')",
                    "insert into barrel_record values(1,4,8,'2026-10-24 11:00:00'),(2,4,8,'2026-10-24 11:00:00'),(3,1,8,'2026-10-23 23:59:59'),(4,9,2,'2026-10-24 09:00:00')",
                    "insert into order_transfer values(1,1,'STAFF','PENDING','RETURN_STATION'),(2,1,'CUSTOMER','PENDING','CANCEL_REQUEST'),(3,1,'STAFF','APPROVED','TRANSFER')")) sql.execute(insert);
        }
        var config = new Configuration(new Environment("synthetic-memory-only", new JdbcTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true); config.addMapper(OrderMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper = session.getMapper(OrderMapper.class);
            if (args.length == 0 || !args[0].equals("--daily-only")) {
                var history = mapper.listBarrelRecords(20L);
                check("history includes delivered unpaid, zero-return and undated legacy rows only for self", ids(history).equals(List.of(1L, 2L, 3L, 4L, 5L, 9L, 10L)));
                check("legacy delivery time remains unknown", history.stream().filter(o -> o.getId() == 9L).findFirst().orElseThrow().getDeliveryTime() == null);
            }
            var today = daily(mapper);
            check("real event window includes unpaid and zero-barrel completion but not later collection", ids(today).equals(List.of(1L, 3L, 4L)));
            check("multiple product ledgers never duplicate an order", today.size() == 3);
            check("return count uses physical deliveries including unpaid", today.stream().mapToInt(Orders::getReturnBucketQty).sum() == 5);
            check("COMPLETE takes precedence over type8 even across midnight", today.stream().filter(o -> o.getId() == 1L).findFirst().orElseThrow().getDeliveryTime().equals(LocalDateTime.parse("2026-10-24T00:00:00")));
            check("wrong module action and non-delivery ledger cannot invent dates", mapper.countDeliveryTimeUnverified(20L) == 2);
            try (var connection = ds.getConnection(); var sql = connection.createStatement()) {
                sql.executeUpdate("update orders set status=4,update_time='2026-10-26 12:00:00' where id=1");
            }
            session.clearCache();
            check("later collection changes neither the delivery date nor daily membership", ids(daily(mapper)).equals(List.of(1L, 3L, 4L)));
            check("pending request current read is scoped to STAFF and pending status", mapper.listPendingStaffRequestsForUpdate(1L).size() == 1);
        }
        System.out.println("AQUAFLOW_DELIVERY_SQL_OK " + passed);
    }
}
