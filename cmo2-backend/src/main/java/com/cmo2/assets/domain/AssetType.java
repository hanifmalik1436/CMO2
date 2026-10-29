package com.cmo2.assets.domain;

/** The seven generated content types. */
public enum AssetType {
    EMAIL_SEQUENCE("Email Sequence"),
    EBOOK("eBook"),
    BLOG_POST("Blog Post"),
    SOCIAL_MEDIA("Social Media"),
    LANDING_PAGE("Landing Page"),
    SMS_CAMPAIGN("SMS Campaign"),
    CHATBOT_SCRIPT("Chatbot Script");

    public final String displayName;

    AssetType(String displayName) {
        this.displayName = displayName;
    }
}
