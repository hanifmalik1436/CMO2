package com.cmo2.integrations.gohighlevel.capability;

/**
 * The fixed list of GoHighLevel actions CMO² may take. Nothing outside this list can be called:
 * AI output can only pick one of these and fill in its typed parameters, never compose a request.
 */
public enum GhlCapability {
    READ_LOCATION(RiskTier.READ, "locations.readonly"),
    COUNT_CONTACTS(RiskTier.READ, "contacts.readonly"),
    LIST_SOCIAL_ACCOUNTS(RiskTier.READ, "socialplanner/account.readonly"),
    LIST_USERS(RiskTier.READ, "users.readonly"),
    LIST_CUSTOM_VALUES(RiskTier.READ, "locations/customValues.readonly"),
    ADD_CONTACT_TAGS(RiskTier.REVERSIBLE_WRITE, "contacts.write"),
    REMOVE_CONTACT_TAGS(RiskTier.REVERSIBLE_WRITE, "contacts.write"),
    UPDATE_CUSTOM_VALUE(RiskTier.REVERSIBLE_WRITE, "locations/customValues.write"),
    CREATE_SOCIAL_POST(RiskTier.CLIENT_FACING, "socialplanner/post.write"),
    DELETE_CONTACT(RiskTier.DESTRUCTIVE, "contacts.write");

    public final RiskTier tier;
    /** The GHL OAuth scope this action needs. */
    public final String scope;

    GhlCapability(RiskTier tier, String scope) {
        this.tier = tier;
        this.scope = scope;
    }
}
