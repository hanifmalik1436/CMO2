package com.cmo2.integrations.gohighlevel.capability;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One concrete request for a {@link GhlCapability}, with typed, validated parameters. Text that
 * came from leads or AI output only ever lands in these fields as data.
 */
public sealed interface GhlAction {

    GhlCapability capability();

    Pattern GHL_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** Social account ids are longer: an OAuth id plus the profile id, e.g. "aF3K…_iVrV…_1255461656452598". */
    Pattern SOCIAL_ACCOUNT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,200}$");
    int MAX_MEDIA = 10;
    int MAX_TAGS = 20;
    int MAX_TAG_LENGTH = 100;

    record ReadLocation() implements GhlAction {
        public GhlCapability capability() { return GhlCapability.READ_LOCATION; }
    }

    record CountContacts() implements GhlAction {
        public GhlCapability capability() { return GhlCapability.COUNT_CONTACTS; }
    }

    record ListSocialAccounts() implements GhlAction {
        public GhlCapability capability() { return GhlCapability.LIST_SOCIAL_ACCOUNTS; }
    }

    record ListUsers() implements GhlAction {
        public GhlCapability capability() { return GhlCapability.LIST_USERS; }
    }

    record ListCustomValues() implements GhlAction {
        public GhlCapability capability() { return GhlCapability.LIST_CUSTOM_VALUES; }
    }

    record AddContactTags(String contactId, List<String> tags) implements GhlAction {
        public AddContactTags {
            requireId(contactId, "contactId");
            tags = requireTags(tags);
        }
        public GhlCapability capability() { return GhlCapability.ADD_CONTACT_TAGS; }
    }

    record RemoveContactTags(String contactId, List<String> tags) implements GhlAction {
        public RemoveContactTags {
            requireId(contactId, "contactId");
            tags = requireTags(tags);
        }
        public GhlCapability capability() { return GhlCapability.REMOVE_CONTACT_TAGS; }
    }

    record UpdateCustomValue(String customValueId, String name, String value) implements GhlAction {
        public UpdateCustomValue {
            requireId(customValueId, "customValueId");
            if (value == null) throw new IllegalArgumentException("value is required");
            if (value.length() > 50_000) throw new IllegalArgumentException("value is too long");
        }
        public GhlCapability capability() { return GhlCapability.UPDATE_CUSTOM_VALUE; }
    }

    /**
     * A post scheduled in the customer's Social Planner. {@code userId} is the sub-account user it
     * is posted as (GHL requires one for posts to connected channels).
     */
    record CreateSocialPost(List<String> accountIds, String summary, List<String> mediaUrls, Instant scheduledAt,
                            String userId) implements GhlAction {
        public CreateSocialPost {
            if (accountIds == null || accountIds.isEmpty()) throw new IllegalArgumentException("at least one social account is required");
            for (String id : accountIds) {
                if (id == null || !SOCIAL_ACCOUNT_ID.matcher(id).matches()) {
                    throw new IllegalArgumentException("accountId is not a valid GHL social account id");
                }
            }
            accountIds = List.copyOf(accountIds);
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("post text is required");
            mediaUrls = mediaUrls == null ? List.of() : List.copyOf(mediaUrls);
            if (mediaUrls.size() > MAX_MEDIA) throw new IllegalArgumentException("at most " + MAX_MEDIA + " media files");
            for (String url : mediaUrls) {
                if (url == null || !url.startsWith("https://") || url.length() > 2048) {
                    throw new IllegalArgumentException("media must be https:// links");
                }
            }
            if (scheduledAt == null) throw new IllegalArgumentException("a schedule time is required");
            requireId(userId, "userId");
        }
        public GhlCapability capability() { return GhlCapability.CREATE_SOCIAL_POST; }
    }

    record DeleteContact(String contactId) implements GhlAction {
        public DeleteContact {
            requireId(contactId, "contactId");
        }
        public GhlCapability capability() { return GhlCapability.DELETE_CONTACT; }
    }

    private static void requireId(String id, String field) {
        if (id == null || !GHL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(field + " is not a valid GHL id");
        }
    }

    private static List<String> requireTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) throw new IllegalArgumentException("at least one tag is required");
        if (tags.size() > MAX_TAGS) throw new IllegalArgumentException("at most " + MAX_TAGS + " tags at once");
        for (String t : tags) {
            if (t == null || t.isBlank() || t.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("tags must be 1-" + MAX_TAG_LENGTH + " characters");
            }
        }
        return List.copyOf(tags);
    }
}
