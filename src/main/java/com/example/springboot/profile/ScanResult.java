package com.example.springboot.profile;

import java.util.List;

public record ScanResult(String profile, long version, List<Long> matches) {
}
