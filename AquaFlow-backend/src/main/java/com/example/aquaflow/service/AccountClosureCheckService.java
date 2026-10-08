package com.example.aquaflow.service;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.AccountClosureCheckMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.vo.AccountClosureCheckVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Authenticated customer only. Read failures, including transaction completion, never produce a clear result. */
@Service
public class AccountClosureCheckService {
    private final AccountClosureCheckMapper mapper;
    private final TransactionTemplate read;
    private final Clock clock;
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("ORDER_OPEN","有未完成订单"),Map.entry("DEBT","有待收款订单"),
        Map.entry("PAYMENT_PENDING","有付款或收退款凭据待确认"),Map.entry("DEPOSIT","有押金余额待清结"),
        Map.entry("BARREL_RIGHTS","有桶权益待清结"),Map.entry("TICKETS","有剩余水票待清结"),
        Map.entry("BARREL_OWED","有欠桶待处理"),Map.entry("BARREL_IN_TRANSIT","有配送中桶待交接"),
        Map.entry("BARREL_RESERVED","有桶容量用途未结束"),Map.entry("RIGHTS_PURCHASE_PENDING","有押金购买待收款确认"),
        Map.entry("RETURN_PENDING","有退还申请未完成"),Map.entry("EXCEPTION_OPEN","有服务异常未处理完"),
        Map.entry("MANUAL_REVIEW","有记录需水站人工核实"));

    public AccountClosureCheckService(AccountClosureCheckMapper mapper,PlatformTransactionManager manager,Clock clock) {
        this.mapper=mapper;this.clock=clock;this.read=new TransactionTemplate(manager);
        read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public AccountClosureCheckVO checkMyAccount() {
        Long customer=AuthContext.requireCustomerId();
        if(customer==null || customer<=0)throw new BusinessException("当前客户身份无效");
        try {
            AccountClosureCheckVO value=read.execute(status->{
                if(mapper.exists(customer)!=1)throw new IllegalStateException("identity incomplete");
                return aggregate(mapper.facts(customer));
            });
            if(value==null)throw new IllegalStateException("missing check result");
            return value;
        } catch(RuntimeException failure) {
            AccountClosureCheckVO value=new AccountClosureCheckVO();value.setCheckedAt(Instant.now(clock));
            value.setMessage("检查未完成，请稍后重试；不能据此判断未结事项已清结。");return value;
        }
    }
    private AccountClosureCheckVO aggregate(List<Map<String,Object>> rows) {
        if(rows==null)throw new IllegalStateException("missing aggregate");
        AccountClosureCheckVO result=new AccountClosureCheckVO();result.setCheckedAt(Instant.now(clock));
        Map<Long,AccountClosureCheckVO.StationSummary> stations=new LinkedHashMap<>();
        Set<Long> unknownStations=new HashSet<>();
        for(Map<String,Object> row:rows) {
            if(row==null)throw new IllegalStateException("incomplete row");
            Long station=row.get("stationId")==null?null:decimal(row.get("stationId")).longValueExact();
            boolean hasStationName=row.get("stationName") instanceof String name && !name.isBlank();
            String stationName=hasStationName?(String)row.get("stationName"):"历史水站待核实";
            Integer stationStatus=row.get("stationStatus")==null?null:decimal(row.get("stationStatus")).intValueExact();
            if(station!=null && station>0) {
                AccountClosureCheckVO.StationSummary summary=new AccountClosureCheckVO.StationSummary();
                summary.setStationId(station);summary.setStationName(stationName);
                summary.setStationStatus(stationStatus);stations.putIfAbsent(station,summary);
            }
            if((station==null || station<=0 || !hasStationName || stationStatus==null || (stationStatus!=1 && stationStatus!=2)) && unknownStations.add(station))
                result.getBlockingItems().add(block(station,stationName,"MANUAL_REVIEW",1,BigDecimal.ZERO,BigDecimal.ZERO));
            String category=Objects.toString(row.get("category"),"");
            if("FACT".equals(category))continue;
            if(!LABELS.containsKey(category))throw new IllegalStateException("unknown aggregate category");
            long count=decimal(row.get("itemCount")).longValueExact();
            BigDecimal quantity=decimal(row.get("quantity")),amount=decimal(row.get("amount"));
            if(count<=0 || quantity.signum()<0 || amount.signum()<0)throw new IllegalStateException("invalid aggregate");
            result.getBlockingItems().add(block(station,stationName,category,count,quantity,amount));
        }
        boolean manual=result.getBlockingItems().stream().anyMatch(item->"MANUAL_REVIEW".equals(item.getCategory()));
        result.setStations(new ArrayList<>(stations.values()));result.setComplete(!manual);
        result.setClear(result.isComplete() && result.getBlockingItems().isEmpty());
        result.setMessage(manual?"检查未完成，有记录需原水站人工核实；不能据此判断未结事项已清结。":
            result.isClear()?"本次检查未发现未结事项；这不表示账户已注销。":"请先处理下列事项，再重新检查。");return result;
    }
    private AccountClosureCheckVO.BlockingItem block(Long station,String stationName,String category,long count,BigDecimal quantity,BigDecimal amount) {
        AccountClosureCheckVO.BlockingItem item=new AccountClosureCheckVO.BlockingItem();
        item.setStationId(station);item.setStationName(stationName);item.setCategory(category);item.setLabel(LABELS.get(category));
        item.setCount(count);item.setQuantity(quantity);item.setAmount(amount);
        item.setEntry("/pages/service/index");
        item.setEntryLabel("联系原水站核实清结");return item;
    }
    private static BigDecimal decimal(Object value) {
        if(value==null)throw new IllegalStateException("missing numeric aggregate");
        return value instanceof BigDecimal decimal?decimal:new BigDecimal(value.toString());
    }
    // TODO(待拍板)：实际注销、资料匿名化/保留期限和导出核验两种方案差别见 docs/design/16-范围决策与实施路线图.md §12 C-10；
    // 拍板后另建注销编排/认证停用和保留策略，本检查不能直接转为删除授权。
}
