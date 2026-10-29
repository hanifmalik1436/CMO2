package com.cmo2.integrations.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical KPI metric keys stored in kpi_snapshots and rendered on the dashboard. */
public final class Metrics {

    private Metrics() {}

    public static final String LEADS = "leads";                   // new contacts today (derived from contacts_total)
    public static final String CPL = "cpl";                       // cost per lead ($)
    public static final String ROAS = "roas";                     // return on ad spend (x)
    public static final String CTR = "ctr";                       // click-through rate (%)
    public static final String CONVERSION_RATE = "conversion_rate"; // (%) won / (won + lost)
    public static final String APPOINTMENTS = "appointments";     // appointments on today's calendars
    public static final String WON = "won";                       // deals won today (derived from opportunities_won_total)
    public static final String REVENUE = "revenue";               // successful live payments today
    public static final String PIPELINE_VALUE = "pipeline_value"; // value of open opportunities

    // Running totals read straight from GHL. Daily figures (leads, won) are derived from them.
    public static final String CONTACTS_TOTAL = "contacts_total";
    public static final String OPPORTUNITIES_OPEN = "opportunities_open";
    public static final String OPPORTUNITIES_WON_TOTAL = "opportunities_won_total";
    public static final String OPPORTUNITIES_LOST_TOTAL = "opportunities_lost_total";
    public static final String CONVERSATIONS_UNREAD = "conversations_unread";
    public static final String SOCIAL_ACCOUNTS = "social_accounts";

    public static final List<String> ALL =
            List.of(LEADS, CPL, ROAS, CTR, CONVERSION_RATE, APPOINTMENTS, WON, REVENUE, PIPELINE_VALUE);

    /** Display order: the day's business first, then the running totals behind it. */
    public static final List<String> ORDER = List.of(LEADS, WON, REVENUE, APPOINTMENTS, CONVERSION_RATE,
            PIPELINE_VALUE, OPPORTUNITIES_OPEN, CONTACTS_TOTAL, OPPORTUNITIES_WON_TOTAL, OPPORTUNITIES_LOST_TOTAL,
            CONVERSATIONS_UNREAD, SOCIAL_ACCOUNTS, CPL, ROAS, CTR);

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(LEADS, "New leads"),
            Map.entry(CPL, "Cost per lead"),
            Map.entry(ROAS, "ROAS"),
            Map.entry(CTR, "Click-through rate"),
            Map.entry(CONVERSION_RATE, "Close rate"),
            Map.entry(APPOINTMENTS, "Appointments"),
            Map.entry(WON, "Deals won"),
            Map.entry(REVENUE, "Revenue"),
            Map.entry(PIPELINE_VALUE, "Open pipeline value"),
            Map.entry(CONTACTS_TOTAL, "Contacts"),
            Map.entry(OPPORTUNITIES_OPEN, "Open opportunities"),
            Map.entry(OPPORTUNITIES_WON_TOTAL, "Opportunities won (all time)"),
            Map.entry(OPPORTUNITIES_LOST_TOTAL, "Opportunities lost (all time)"),
            Map.entry(CONVERSATIONS_UNREAD, "Unread conversations"),
            Map.entry(SOCIAL_ACCOUNTS, "Social accounts connected"));

    public static String label(String metric) {
        return LABELS.getOrDefault(metric, metric);
    }

    /** Per-day figures: a week's value is their sum. Everything else is a level (latest value counts). */
    public static final Set<String> DAILY = Set.of(LEADS, APPOINTMENTS, WON, REVENUE);

    /** Running total → the daily figure derived from how much it grew since the previous day. */
    public static final List<List<String>> DERIVED_FROM_TOTALS = List.of(
            List.of(CONTACTS_TOTAL, LEADS),
            List.of(OPPORTUNITIES_WON_TOTAL, WON));
}
