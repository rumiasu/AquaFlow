package com.example.aquaflow.service;

import com.example.aquaflow.dto.AccountDataRequestDTO;
import com.example.aquaflow.entity.AccountDataRequest;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.AccountDataRequestMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.vo.AccountClosureCheckVO;
import com.example.aquaflow.vo.AccountDataRequestVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** Registration preparation, with no deletion/export/credential command dependencies. */
@Service
public class AccountDataRequestService {
    public record RequestType(String value,String label) {}
    public record Options(boolean intakeEnabled, String intakeContact, String notice, boolean closureCheckSupported,List<RequestType> requestTypes) {}
    public record Page(List<AccountDataRequestVO> items, Long nextBeforeId) {}
    public record Detail(AccountDataRequestVO request, AccountClosureCheckVO closureCheck, String checkNotice) {}
    private record Actor(String type,Long id) {}
    private static final Set<String> TYPES=Set.of("ACCESS","CORRECTION","EXPORT","DELETION","CLOSURE");
    private final AccountDataRequestMapper mapper;
    private final AccountClosureCheckService closure;
    private final Clock clock;
    private final boolean enabled;
    private final String owner,contact;
    public AccountDataRequestService(AccountDataRequestMapper mapper,AccountClosureCheckService closure,Clock clock,
            @Value("${app.account-data-requests.enabled:false}") boolean enabled,
            @Value("${app.account-data-requests.intake-owner:}") String owner,
            @Value("${app.account-data-requests.intake-contact:}") String contact) {
        this.mapper=mapper;this.closure=closure;this.clock=clock;this.enabled=enabled;
        this.owner=owner==null?"":owner.trim();this.contact=contact==null?"":contact.trim();
        // TODO(待拍板)：真实受理人/渠道按 docs/design/16-范围决策与实施路线图.md §12 C-09，留存、字段处理和核验按 C-10。
        // Merely enabling a switch must not invent a recipient or promised processing deadline.
        if(enabled && (!configured(this.owner) || !configured(this.contact)))
            throw new IllegalStateException("资料请求启用前必须配置已核实的受理人和联系渠道");
    }
    private static boolean configured(String text) {
        return !text.isBlank() && !text.contains("待补") && !text.contains("待拍板") && !text.contains("example.com") && !text.contains("your-");
    }
    private Actor actor() {
        String type=AuthContext.getUserType();Long id=AuthContext.getUserId();
        if(id==null || id<=0 || (!"customer".equals(type) && !"staff".equals(type))) throw new BusinessException("请先建立本人账户身份");
        int exists="customer".equals(type)?mapper.customerExists(id):mapper.staffExists(id);
        if(exists!=1)throw new BusinessException("本人账户不存在，请重新登录核实");
        return new Actor(type,id);
    }
    public Options options() {
        Actor actor=actor();
        return new Options(enabled,enabled?contact:null,enabled?"申请登记后等待人工核实，本入口不会自动处理资料或注销账户。"
                :"资料请求受理尚未启用，受理人和联系渠道待核实；本人注销前检查仍可单独使用。","customer".equals(actor.type),
                List.of(new RequestType("ACCESS","查询资料"),new RequestType("CORRECTION","更正资料"),new RequestType("EXPORT","导出资料"),
                        new RequestType("DELETION","删除资料"),new RequestType("CLOSURE","账户注销申请")));
    }
    @Transactional
    public AccountDataRequestVO submit(AccountDataRequestDTO dto) {
        Actor actor=actor();String key=text(dto.getIdempotencyKey(),64),note=text(dto.getNote(),500),type=dto.getRequestType();
        if(!key.matches("[A-Za-z0-9._:-]{1,64}") || type==null || !TYPES.contains(type))throw new BusinessException("请填写有效请求类型和提交编号");
        String digest=digest(type,note);
        AccountDataRequest replay=mapper.findReplay(actor.type,actor.id,key);
        if(replay!=null)return same(replay,digest); // A paused intake still permits querying a previous request.
        if(!enabled)throw new BusinessException("资料请求受理尚未启用，不能登记申请；本人注销前检查可单独使用");
        AccountDataRequest value=new AccountDataRequest();value.setActorType(actor.type);value.setActorId(actor.id);
        value.setRequestType(type);value.setNote(note);value.setIdempotencyKey(key);value.setRequestDigest(digest);
        value.setStatus("SUBMITTED");value.setCreateTime(LocalDateTime.now(clock));mapper.insert(value);
        // Current read after a duplicate-key wait, not the transaction's older repeatable-read snapshot.
        replay=mapper.findReplayForUpdate(actor.type,actor.id,key);
        if(replay==null)throw new BusinessException("资料请求未登记成功，请使用原提交编号重试");
        return same(replay,digest);
    }
    public Page mine(Long beforeId) {
        Actor actor=actor();if(beforeId!=null && beforeId<=0)throw new BusinessException("查询位置无效");
        List<AccountDataRequest> rows=mapper.listMine(actor.type,actor.id,beforeId);
        boolean more=rows.size()>50;List<AccountDataRequest> page=rows.subList(0,Math.min(50,rows.size()));
        return new Page(page.stream().map(this::view).toList(),more?page.get(page.size()-1).getId():null);
    }
    public Detail detail(Long requestId) {
        Actor actor=actor();if(requestId==null || requestId<=0)throw denied();
        AccountDataRequest value=mapper.mine(requestId,actor.type,actor.id);if(value==null)throw denied();
        if("CLOSURE".equals(value.getRequestType()) && "customer".equals(actor.type))
            return new Detail(view(value),closure.checkMyAccount(),"未结检查是当前业务提示，未结或检查失败不表示申请被拒绝，也不授权注销。");
        return new Detail(view(value),null,"CLOSURE".equals(value.getRequestType())?"员工未结业务需人工核实，本入口未完成员工清结检查。":null);
    }
    private AccountDataRequestVO same(AccountDataRequest value,String digest) {
        if(!digest.equals(value.getRequestDigest()))throw new BusinessException("同一提交编号不能修改资料申请内容，请使用新的编号");
        return view(value);
    }
    private AccountDataRequestVO view(AccountDataRequest value) {
        String typeText=switch(value.getRequestType()) { case "ACCESS"->"查询资料";case "CORRECTION"->"更正资料";
            case "EXPORT"->"导出资料";case "DELETION"->"删除资料";case "CLOSURE"->"账户注销申请";default->"请求类型待核实"; };
        return new AccountDataRequestVO(value.getId(),value.getRequestType(),typeText,value.getNote(),value.getStatus(),
                "SUBMITTED".equals(value.getStatus())?"已登记，待人工核实":"状态待人工核实",value.getCreateTime(),
                "仅登记请求，不表示资料已导出、更正、删除或匿名化；账户、凭据和业务台账不会由本入口改变。");
    }
    private BusinessException denied() {return new BusinessException("资料请求不存在或无权查看");}
    private String text(String input,int max) {
        String value=input==null?"":input.trim();if(value.length()>max)throw new BusinessException("提交编号或补充说明过长");return value;
    }
    private String digest(String type,String note) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((type.length()+":"+type+note.length()+":"+note).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
