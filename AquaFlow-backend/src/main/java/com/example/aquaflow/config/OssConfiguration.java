package com.example.aquaflow.config;

import com.example.aquaflow.util.CosUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class OssConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CosUtil cosUtil(CosProperties cosProperties) {
        log.info("开始创建腾讯云COS文件上传工具类对象");
        return new CosUtil(
                cosProperties.getRegion(),
                cosProperties.getSecretId(),
                cosProperties.getSecretKey(),
                cosProperties.getBucketName()
        );
    }
}
