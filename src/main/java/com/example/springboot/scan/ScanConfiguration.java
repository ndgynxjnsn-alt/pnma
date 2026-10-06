package com.example.springboot.scan;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScanProperties.class)
public class ScanConfiguration {

	@Bean(destroyMethod = "close")
	ScanExecutorService scanExecutorService(ScanProperties properties) {
		return new ScanExecutorService(properties.effectiveConcurrency());
	}

}
