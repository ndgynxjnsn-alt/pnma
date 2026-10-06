package com.example.springboot.profile;

import java.util.List;

public record ProfileInfo(long version, List<PatternDefinition> patterns) {
}
