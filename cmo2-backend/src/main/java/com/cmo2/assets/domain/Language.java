package com.cmo2.assets.domain;

/** Bilingual pipeline: generate in English, auto-translate to Spanish. */
public enum Language {
    EN("English"),
    ES("Spanish");

    public final String displayName;

    Language(String displayName) {
        this.displayName = displayName;
    }
}
