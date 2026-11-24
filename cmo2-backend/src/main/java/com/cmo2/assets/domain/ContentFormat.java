package com.cmo2.assets.domain;

/**
 * The Content Studio's formats: one entry per platform and post type, each with the instructions
 * its AI agent writes to. {@code publishable} formats are single posts GoHighLevel's Social Planner
 * can schedule ({@code ghlPlatform} names the matching social account type); the rest (threads,
 * carousels, scripts, articles, newsletters) are approved in CMO² and copied to the platform.
 */
public enum ContentFormat {

    X_POST("X", "X", "Post", AssetType.SOCIAL_MEDIA, true, "twitter", 280,
            "Write one post of at most 280 characters. Lead with the hook in the first line. Plain, punchy "
                    + "language; at most one hashtag; no emoji walls. End with a reason to reply or click."),
    X_THREAD("X", "X", "Thread", AssetType.SOCIAL_MEDIA, false, null, 0,
            "Write a thread of 5 to 8 posts, numbered 1/, 2/ … Each post at most 280 characters. Post 1 is "
                    + "the hook and promises a payoff; each following post delivers one point; the last post sums up "
                    + "and invites a reply or follow."),
    X_LONG_POST("X", "X", "Long post", AssetType.SOCIAL_MEDIA, false, null, 4000,
            "Write one long X post of 800 to 1,500 characters (for accounts that can post beyond 280). The first "
                    + "line must work alone as the hook. Short paragraphs, one idea, a concrete example, and a closing "
                    + "line that invites replies. No hashtags beyond one."),
    X_ARTICLE("X", "X", "Article", AssetType.BLOG_POST, false, null, 0,
            "Write an X article of 700 to 1,000 words: a headline (max 80 characters), a one-line subtitle, an "
                    + "opening that states the problem, 3 to 5 sections with short headings, and a closing takeaway "
                    + "with one call to action. Use markdown."),
    LINKEDIN_POST("LINKEDIN", "LinkedIn", "Post", AssetType.SOCIAL_MEDIA, true, "linkedin", 3000,
            "Write a LinkedIn post of 120 to 250 words. First line is a hook that works before 'see more'. "
                    + "Short paragraphs of one or two sentences, white space between them. Professional but human; "
                    + "one concrete example. End with a question. Up to 3 relevant hashtags on the last line."),
    LINKEDIN_CAROUSEL("LINKEDIN", "LinkedIn", "Carousel", AssetType.CAROUSEL, false, null, 0,
            "Write copy for a 7 to 9 slide document carousel. Format each slide as 'Slide N:' followed by a "
                    + "headline (max 8 words) and at most two short lines. Slide 1 is the hook, the last slide is the "
                    + "call to action. Then a 2 to 3 sentence post caption to go with it."),
    LINKEDIN_ARTICLE("LINKEDIN", "LinkedIn", "Article", AssetType.BLOG_POST, false, null, 0,
            "Write a LinkedIn article of 700 to 1,000 words: a headline (max 100 characters), an opening story or "
                    + "observation, 3 to 4 sections with headings, practical advice, and a closing question for "
                    + "comments. Professional, first person, no jargon. Use markdown."),
    LINKEDIN_NEWSLETTER("LINKEDIN", "LinkedIn", "Newsletter", AssetType.NEWSLETTER, false, null, 0,
            "Write a LinkedIn newsletter edition of 500 to 800 words: a title (max 80 characters), a one-line "
                    + "teaser, a personal opening, the main idea with one example, 3 takeaways as a short list, and a "
                    + "sign-off asking readers to reply or subscribe."),
    YOUTUBE_SHORT("YOUTUBE", "YouTube", "Short script", AssetType.VIDEO_SCRIPT, false, null, 0,
            "Write a YouTube Shorts script for 30 to 45 seconds, about 90 to 120 spoken words. Mark sections "
                    + "[HOOK 0-3s], [BODY], [CTA]. Add on-screen text cues in brackets. Then a title (max 60 characters)."),
    YOUTUBE_VIDEO("YOUTUBE", "YouTube", "Video script", AssetType.VIDEO_SCRIPT, false, null, 0,
            "Write a 5 to 7 minute YouTube video script: a cold-open hook, an intro, 3 to 5 sections with "
                    + "headings, and a closing call to action. Then a title (max 70 characters), a description of "
                    + "2 short paragraphs and 5 chapter timestamps (estimated)."),
    YOUTUBE_OUTLINE("YOUTUBE", "YouTube", "Long video outline", AssetType.VIDEO_SCRIPT, false, null, 0,
            "Write an outline for an 8 to 12 minute YouTube video: 3 title options (max 70 characters), a "
                    + "thumbnail text idea (max 4 words), the cold-open hook word for word, then 4 to 6 sections each "
                    + "with talking points and one example, and the closing call to action."),
    YOUTUBE_COMMUNITY_POST("YOUTUBE", "YouTube", "Community post", AssetType.SOCIAL_MEDIA, false, null, 1500,
            "Write a YouTube Community post of 40 to 120 words that starts a conversation with subscribers: a "
                    + "hook, one point of view, and either a question or a 3-option poll (list the options)."),
    TIKTOK_SCRIPT("TIKTOK", "TikTok", "Video script", AssetType.VIDEO_SCRIPT, false, null, 0,
            "Write a TikTok script for 20 to 40 seconds. Open with a pattern-interrupt hook in the first 2 "
                    + "seconds. Mark [HOOK], [BODY], [CTA] and add on-screen text cues in brackets. Then a caption "
                    + "under 150 characters with 3 to 5 hashtags."),
    THREADS_POST("THREADS", "Threads", "Post", AssetType.SOCIAL_MEDIA, true, "threads", 500,
            "Write one Threads post of at most 500 characters. Conversational, opinionated, no hashtags "
                    + "needed. End with a question that invites replies."),
    INSTAGRAM_CAPTION("INSTAGRAM", "Instagram", "Feed caption", AssetType.SOCIAL_MEDIA, true, "instagram", 2200,
            "Write an Instagram feed caption of 80 to 180 words. First line is the hook. Short lines, a few "
                    + "relevant emoji at most. End with a clear call to action, then 5 to 10 relevant hashtags on "
                    + "their own line. Add a one-line suggestion for the image in brackets at the top."),
    INSTAGRAM_CAROUSEL("INSTAGRAM", "Instagram", "Carousel", AssetType.CAROUSEL, false, null, 0,
            "Write copy for a 6 to 10 slide Instagram carousel. Format each slide as 'Slide N:' with a "
                    + "headline (max 8 words) and at most two short lines. Slide 1 stops the scroll; the last slide "
                    + "asks for a save, share or comment. Then a short caption with 5 to 10 hashtags."),
    INSTAGRAM_REEL("INSTAGRAM", "Instagram", "Reel script", AssetType.VIDEO_SCRIPT, false, null, 0,
            "Write an Instagram Reel script for 15 to 30 seconds. Mark [HOOK], [BODY], [CTA], with on-screen "
                    + "text cues in brackets. Then a caption of 1 to 3 sentences and 5 to 8 hashtags."),
    FACEBOOK_POST("FACEBOOK", "Facebook", "Post", AssetType.SOCIAL_MEDIA, true, "facebook", 2000,
            "Write a Facebook post of 60 to 150 words for a local, community audience. Warm and specific; "
                    + "one clear call to action (comment, message or book). At most 2 hashtags."),
    GOOGLE_BUSINESS_POST("GOOGLE", "Google Business", "Update post", AssetType.SOCIAL_MEDIA, true, "google", 1500,
            "Write a Google Business Profile update of 80 to 150 words for people searching locally. Mention "
                    + "the service and area naturally, one concrete benefit, and a call to action (call, book, visit). "
                    + "No hashtags."),
    SITE_ARTICLE("SITE", "Website", "Blog article", AssetType.BLOG_POST, false, null, 0,
            "Write a blog article of 900 to 1,200 words. Start with an SEO title (max 60 characters) and a meta "
                    + "description (max 155 characters). Use an introduction, 3 to 5 sections with H2 headings, short "
                    + "paragraphs, and a conclusion with a call to action. Use markdown."),
    SITE_NEWSLETTER("SITE", "Website", "Email newsletter", AssetType.NEWSLETTER, false, null, 0,
            "Write an email newsletter of 300 to 500 words for the business's own list: a subject line (max 50 "
                    + "characters), a preview line (max 90 characters), a short personal opening, one useful idea "
                    + "with an example, and one clear call to action button text."),
    SITE_LEAD_MAGNET("SITE", "Website", "Lead magnet", AssetType.EBOOK, false, null, 0,
            "Write a short lead magnet (a checklist or one-page guide) of 400 to 700 words: a title that names the "
                    + "result, who it's for, 5 to 10 numbered, practical steps or checks, and a closing call to "
                    + "action to get in touch. Use markdown."),
    SUBSTACK_NEWSLETTER("SUBSTACK", "Substack", "Newsletter issue", AssetType.NEWSLETTER, false, null, 0,
            "Write a newsletter issue of 500 to 800 words: a subject line (max 60 characters), a preview line, "
                    + "a personal opening, the main idea with one story or example, 3 practical takeaways, and a "
                    + "sign-off with one call to action."),
    SUBSTACK_POST("SUBSTACK", "Substack", "Note", AssetType.SOCIAL_MEDIA, false, null, 1000,
            "Write a Substack Note of 50 to 200 words: one sharp observation or opinion, written to be restacked, "
                    + "ending with a line that makes readers want the full newsletter.");

    public final String platform;
    public final String platformLabel;
    public final String label;
    public final AssetType assetType;
    /** True when GoHighLevel's Social Planner can schedule it as a single post. */
    public final boolean publishable;
    /** The GHL social account platform it publishes to ("twitter", "linkedin"…), or null. */
    public final String ghlPlatform;
    /** Hard character limit of the platform's post, or 0 when there isn't one. */
    public final int maxChars;
    /** The format agent's instructions. */
    public final String guidance;

    ContentFormat(String platform, String platformLabel, String label, AssetType assetType, boolean publishable,
                  String ghlPlatform, int maxChars, String guidance) {
        this.platform = platform;
        this.platformLabel = platformLabel;
        this.label = label;
        this.assetType = assetType;
        this.publishable = publishable;
        this.ghlPlatform = ghlPlatform;
        this.maxChars = maxChars;
        this.guidance = guidance;
    }

    /** The format for a stored name, or null if it isn't one (or is null). */
    public static ContentFormat of(String name) {
        if (name == null) return null;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
