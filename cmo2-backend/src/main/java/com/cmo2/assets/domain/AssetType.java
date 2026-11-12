package com.cmo2.assets.domain;

/** Generated content types. The last three come from the Content Studio. */
public enum AssetType {
    EMAIL_SEQUENCE("Email Sequence"),
    EBOOK("eBook"),
    BLOG_POST("Blog Post"),
    SOCIAL_MEDIA("Social Media"),
    LANDING_PAGE("Landing Page"),
    SMS_CAMPAIGN("SMS Campaign"),
    CHATBOT_SCRIPT("Chatbot Script"),
    // Content Studio formats that aren't a single post:
    VIDEO_SCRIPT("Video Script"),
    CAROUSEL("Carousel"),
    NEWSLETTER("Newsletter");

    public final String displayName;

    AssetType(String displayName) {
        this.displayName = displayName;
    }
}
