package com.example.springboot.profile;

import java.util.List;

public record ProfileInfo(String name, long version, List<PatternDefinition> patterns) {
}
