package com.example.springboot.profile;

import java.util.List;

public record ScanResult(long version, List<Long> matches) {
}
