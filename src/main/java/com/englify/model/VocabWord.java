package com.englify.model;

public record VocabWord(long id, String english, String ukrainian, String level,
                        String topic, String source, boolean custom) { }
