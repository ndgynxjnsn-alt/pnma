package com.example.springboot.scan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param maxConcurrency number of scans running in parallel, defaults to the number of available cores
 * @param maxContentBytes largest content accepted for a single scan
 */
@ConfigurationProperties("scan")
public record ScanProperties(Integer maxConcurrency, @DefaultValue("16MB") DataSize maxContentBytes) {

	public int effectiveConcurrency() {
		return (this.maxConcurrency != null) ? this.maxConcurrency : ScanExecutorService.defaultConcurrency();
	}

}
