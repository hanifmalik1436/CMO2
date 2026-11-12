package com.cmo2.integrations.gohighlevel;

import com.cmo2.shared.exceptions.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Runtime switch for GoHighLevel integration surfaces.
 * agency: full Marketplace agency OAuth + sub-account onboarding.
 * single-subaccount: direct per-company key/location integration for development.
 */
@Service
public class GhlIntegrationModeService {

    public enum Mode {
        AGENCY("agency"),
        SINGLE_SUBACCOUNT("single-subaccount");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final Mode mode;

    public GhlIntegrationModeService(@Value("${cmo2.ghl.mode:agency}") String configured) {
        this.mode = parse(configured);
    }

    public String mode() {
        return mode.value();
    }

    public boolean agencyEnabled() {
        return mode == Mode.AGENCY;
    }

    public void requireAgencyMode() {
        if (agencyEnabled()) {
            return;
        }
        throw ApiException.conflict("Agency mode is disabled in this environment (CMO2_GHL_MODE=single-subaccount)");
    }

    private static Mode parse(String configured) {
        String v = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "agency" -> Mode.AGENCY;
            case "single-subaccount", "single_subaccount", "single" -> Mode.SINGLE_SUBACCOUNT;
            default -> throw new IllegalArgumentException(
                    "Invalid cmo2.ghl.mode '" + configured + "'. Use: agency | single-subaccount");
        };
    }
}
