package com.englify.model;

/** Optional editorial metadata: no missing pronunciation or example is fabricated. */
public record WordDetails(String pos, String ipa, String exampleEn, String exampleUk, String audioPath) { }
