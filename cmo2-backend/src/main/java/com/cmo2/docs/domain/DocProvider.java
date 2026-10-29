package com.cmo2.docs.domain;

/** The workspace tools CMO² writes to. */
public enum DocProvider {
    NOTION("Notion"),
    CONFLUENCE("Confluence"),
    LINEAR("Linear"),
    SLACK("Slack");

    public final String displayName;

    DocProvider(String displayName) {
        this.displayName = displayName;
    }
}
