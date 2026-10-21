package com.example.aquaflow.support;

import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.payment.PaymentTransactionIdentity;
import com.example.aquaflow.payment.wechat.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** 仅合成测试：内存生成密钥，实际SDK验签/解密/匹配，无网络或真实商户配置。 */
public final class SyntheticWechatSuccess {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final KeyPair KEYS;
    private static final String API_KEY;
    private static final String SERIAL="PUB_KEY_ID_c08_synthetic";
    static {
        try {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KEYS=generator.generateKeyPair();byte[] random=new byte[16];new SecureRandom().nextBytes(random);API_KEY=HexFormat.of().formatHex(random);}
        catch(GeneralSecurityException failure){throw new ExceptionInInitializerError(failure);}
    }
    public record Proof(PaymentTransactionIdentity identity,WechatPaySuccessMatcher.MatchedPayment matched) {
        @Override public String toString(){return "SyntheticWechatProof[redacted]";}
    }
    public static Proof proof(PaymentRecord payment,Instant paidAt) throws Exception {
        return proof(payment,paidAt,"fixture-tx-"+payment.getId(),"fixture-bind-"+payment.getStationId(),payment.getAmount().movePointRight(2).intValueExact());
    }
    public static Proof proof(PaymentRecord payment,Instant paidAt,String transaction,String binding,Integer payerCents) throws Exception {
        var identity=new PaymentTransactionIdentity(payment.getId(),payment.getOrderId(),binding,"fixture-pay-"+payment.getId(),payment.getAmount(),"CNY",null);
        var merchant=new WechatPaySuccessMatcher.DirectJsapi("merchant-c08-synthetic","wx-c08-synthetic");
        ObjectNode tx=JSON.createObjectNode().put("mchid",merchant.merchantId()).put("appid",merchant.appId()).put("out_trade_no",identity.outTradeNo()).put("transaction_id",transaction).put("trade_state","SUCCESS").put("trade_type","JSAPI");
        ObjectNode amount=JSON.createObjectNode().put("total",payment.getAmount().movePointRight(2).intValueExact()).put("currency","CNY");if(payerCents!=null)amount.put("payer_total",payerCents).put("payer_currency","CNY");tx.set("amount",amount);if(paidAt!=null)tx.put("success_time",paidAt.toString());
        byte[] nonceBytes=new byte[6];new SecureRandom().nextBytes(nonceBytes);String nonce=HexFormat.of().formatHex(nonceBytes);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(API_KEY.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        ObjectNode resource=JSON.createObjectNode().put("original_type","transaction").put("algorithm","AEAD_AES_256_GCM").put("nonce",nonce).put("associated_data","transaction").put("ciphertext",Base64.getEncoder().encodeToString(cipher.doFinal(tx.toString().getBytes(StandardCharsets.UTF_8))));
        ObjectNode event=JSON.createObjectNode().put("id","c08-fixture-event").put("event_type","TRANSACTION.SUCCESS").put("resource_type","encrypt-resource");event.set("resource",resource);String body=event.toString(),timestamp=String.valueOf(Instant.now().getEpochSecond()),headerNonce="c08-fixture";
        Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(KEYS.getPrivate());signer.update((timestamp+"\n"+headerNonce+"\n"+body+"\n").getBytes(StandardCharsets.UTF_8));
        var headers=new WechatPayNotificationAdapter.Headers(SERIAL,timestamp,headerNonce,Base64.getEncoder().encodeToString(signer.sign()),null);
        var adapter=new WechatPayNotificationAdapter(KEYS.getPublic(),SERIAL,API_KEY,Clock.fixed(Instant.ofEpochSecond(Long.parseLong(timestamp)),ZoneOffset.UTC));
        var verified=adapter.parseUtf8(headers,body.getBytes(StandardCharsets.UTF_8));
        return new Proof(identity,new WechatPaySuccessMatcher().match(verified,identity.forMatching(merchant)));
    }
    private SyntheticWechatSuccess(){}
}
