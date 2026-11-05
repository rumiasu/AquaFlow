package com.example.aquaflow.support;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.CosUtil;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 测试专用 COS fail-closed 隔离。
 *
 * <p>为什么不能只清空 {@code COS_SECRET_ID/KEY}：环境变量仍可能覆盖，且删除 SDK
 * 即使没有有效密钥也会尝试连接真实 endpoint。本配置用离线 mock 替换整个
 * {@link CosUtil}，上传/删除一律抛业务异常，URL 生成只返回本地合成地址，
 * 从测试侧保证不会访问任何真实 COS。</p>
 *
 * <p>只用于测试上下文；不改变生产代码或生产配置。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class OfflineCosTestConfiguration {

    /** 与生产未配置文案保持一致，避免测试依赖不同文案。 */
    public static final String OFFLINE_MESSAGE =
            "对象存储未配置，图片上传不可用（请补 COS_SECRET_ID / COS_SECRET_KEY）";

    @Bean
    @Primary
    public CosUtil offlineCosUtil() {
        CosUtil cos = Mockito.mock(CosUtil.class);
        when(cos.isCredentialsConfigured()).thenReturn(false);
        when(cos.uploadFailureMessage()).thenReturn(OFFLINE_MESSAGE);
        when(cos.upload(any(), any())).thenThrow(new BusinessException("测试隔离：禁止访问真实 COS"));
        when(cos.uploadPublic(any(), any(), any())).thenThrow(new BusinessException("测试隔离：禁止访问真实 COS"));
        when(cos.uploadPrivate(any(), any(), any())).thenThrow(new BusinessException("测试隔离：禁止访问真实 COS"));
        doThrow(new BusinessException("测试隔离：禁止访问真实 COS")).when(cos).delete(any());
        when(cos.generatePublicUrl(any())).thenAnswer(call -> offlineUrl(call.getArgument(0)));
        when(cos.generatePrivateUrl(any())).thenAnswer(call -> offlineUrl(call.getArgument(0)));
        return cos;
    }

    private static String offlineUrl(Object objectName) {
        return objectName == null || objectName.toString().isBlank()
                ? null : "https://offline.invalid/" + objectName;
    }
}
