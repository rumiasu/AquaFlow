import com.example.aquaflow.dto.RecordDisplayNameRow;
import com.example.aquaflow.mapper.RecordDisplayNameMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import java.sql.*;
import java.util.*;

// 只连接本JVM内存H2，执行编译后的实际MyBatis Mapper；不加载Spring或任何业务数据源。
public class RecordDisplayNameSqlProbe {
  static int passed;
  static void check(String name, boolean value) { if (!value) throw new AssertionError(name); passed++; System.out.println("PASS "+name); }
  public static void main(String[] args) throws Exception {
    var ds = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:reference_names;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    try (var connection=ds.getConnection(); var sql=connection.createStatement()) {
      for (String ddl:List.of(
          "create table station(id bigint primary key,name varchar(80))",
          "create table customer(id bigint primary key,name varchar(80))",
          "create table customer_station_config(customer_id bigint,station_id bigint)",
          "create table product(id bigint primary key,owner_station_id bigint,name varchar(80),spec varchar(40),status int)",
          "create table orders(id bigint primary key,customer_id bigint,station_id bigint,delivery_station_id bigint,receiver_name varchar(80))",
          "create table order_barrel_exception(id bigint primary key,order_id bigint,customer_id bigint,station_id bigint)",
          "create table barrel_record(id bigint primary key,customer_id bigint,station_id bigint,product_id bigint,type int)")) sql.execute(ddl);
      for (String insert:List.of(
          "insert into station values(11,'本站'),(22,'合作站'),(33,'无关站')",
          "insert into customer values(7,'客户档案甲'),(8,'仅绑定客户'),(9,'他站客户')",
          "insert into customer_station_config values(7,22),(8,11),(9,22)",
          "insert into product values(5,null,'公共商品','18.5L',1),(6,11,'本站私有商品','12L',1),(7,22,'他站私有商品','12L',1),(8,11,'已下架历史商品','18.5L',0)",
          "insert into orders values(81,7,11,22,'订单收件人甲'),(82,9,22,22,'他站收件人')",
          "insert into order_barrel_exception values(1,81,7,11),(2,81,7,22),(3,81,7,33),(4,999,7,11)",
          "insert into barrel_record values(1,8,11,5,2),(2,7,11,6,2),(3,9,11,7,2),(4,99,11,99,2),(5,8,11,8,2),(6,8,22,5,2),(7,8,11,5,8)")) sql.execute(insert);
    }
    var config=new Configuration(new Environment("memory-only",new JdbcTransactionFactory(),ds));
    config.setMapUnderscoreToCamelCase(true); config.addMapper(RecordDisplayNameMapper.class);
    try (var session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
      var mapper=session.getMapper(RecordDisplayNameMapper.class);
      var own=mapper.exceptionNames(11L,List.of(1L)).get(0);
      check("own exception profile and order receiver stay distinct", "客户档案甲".equals(own.getCustomerName()) && "订单收件人甲".equals(own.getReceiverName()));
      var cross=mapper.exceptionNames(22L,List.of(2L)).get(0);
      check("cross-station profile masked even if customer is bound to delivery station", cross.getCustomerName()==null && "订单收件人甲".equals(cross.getReceiverName()));
      var unrelated=mapper.exceptionNames(33L,List.of(3L)).get(0);
      check("unrelated order supplies no profile or snapshot", unrelated.getCustomerName()==null && unrelated.getReceiverName()==null);
      check("exception station and id both constrain result", mapper.exceptionNames(22L,List.of(1L)).isEmpty());
      check("empty and null exception ids never enumerate", mapper.exceptionNames(11L,List.of()).isEmpty() && mapper.exceptionNames(11L,null).isEmpty());
      check("null station does not read names", mapper.exceptionNames(null,List.of(1L)).isEmpty() && mapper.barrelNames(null,List.of(1L)).isEmpty());
      var missingOrder=mapper.exceptionNames(11L,List.of(4L)).get(0);
      check("missing historical order does not borrow a current profile", missingOrder.getCustomerName()==null && missingOrder.getReceiverName()==null);
      var bound=mapper.barrelNames(11L,List.of(1L)).get(0);
      check("binding-only asset customer and global product readable", "仅绑定客户".equals(bound.getCustomerName()) && "公共商品".equals(bound.getProductName()) && "本站".equals(bound.getStationName()));
      var ordered=mapper.barrelNames(11L,List.of(2L)).get(0);
      check("owned order authorizes profile and own product", "客户档案甲".equals(ordered.getCustomerName()) && "本站私有商品".equals(ordered.getProductName()));
      var foreign=mapper.barrelNames(11L,List.of(3L)).get(0);
      check("foreign-only profile and private product withheld", foreign.getCustomerName()==null && foreign.getProductName()==null);
      var deleted=mapper.barrelNames(11L,List.of(4L)).get(0);
      check("deleted references keep record id but no invented names", deleted.getId()==4L && deleted.getCustomerName()==null && deleted.getProductName()==null);
      check("retired own product remains a valid historical label", "已下架历史商品".equals(mapper.barrelNames(11L,List.of(5L)).get(0).getProductName()));
      check("foreign records and delivery ledgers are excluded", mapper.barrelNames(11L,List.of(6L,7L)).isEmpty());
      check("empty and null barrel ids never enumerate", mapper.barrelNames(11L,List.of()).isEmpty() && mapper.barrelNames(11L,null).isEmpty());
    }
    System.out.println("AQUAFLOW_SQL_PROBE_OK "+passed);
  }
}
