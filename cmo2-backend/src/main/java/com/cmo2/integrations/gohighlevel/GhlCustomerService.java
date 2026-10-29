package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.core.Platform;
import com.cmo2.integrations.domain.PlatformConnection;
import com.cmo2.integrations.events.CustomerEnabledEvent;
import com.cmo2.integrations.gohighlevel.GhlAgencyService.GhlLocation;
import com.cmo2.integrations.gohighlevel.GhlOAuthClient.TokenGrant;
import com.cmo2.integrations.repository.PlatformConnectionRepository;
import com.cmo2.integrations.service.ConnectionService;
import com.cmo2.shared.exceptions.ApiException;
import com.cmo2.tenancy.domain.Company;
import com.cmo2.tenancy.dto.CompanyDtos.CompanyResponse;
import com.cmo2.tenancy.dto.CompanyDtos.CreateCompanyRequest;
import com.cmo2.tenancy.repository.CompanyRepository;
import com.cmo2.tenancy.service.CompanyService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The owner's customer list: every sub-account under the agency, and which of them are enabled
 * in CMO². Enabling a sub-account creates its customer (a company) and connects it through the
 * agency.
 */
@Service
public class GhlCustomerService {

    private static final Pattern LOCATION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final int MAX_NAME = 160;

    private final GhlAgencyService agency;
    private final CompanyService companyService;
    private final CompanyRepository companyRepo;
    private final ConnectionService connectionService;
    private final PlatformConnectionRepository connectionRepo;
    private final ApplicationEventPublisher events;

    public GhlCustomerService(GhlAgencyService agency, CompanyService companyService, CompanyRepository companyRepo,
                              ConnectionService connectionService, PlatformConnectionRepository connectionRepo,
                              ApplicationEventPublisher events) {
        this.agency = agency;
        this.companyService = companyService;
        this.companyRepo = companyRepo;
        this.connectionService = connectionService;
        this.connectionRepo = connectionRepo;
        this.events = events;
    }

    public record SubAccount(String locationId, String name, String email, String phone, String website,
                             String timezone, String city, String country,
                             boolean enabled, Long companyId, Long connectionId,
                             String health, Instant lastSyncAt, String lastError) {}

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public List<SubAccount> listSubAccounts() {
        List<GhlLocation> locations = agency.listLocations();
        Map<String, Company> enabled = companyRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> c.getGhlLocationId() != null)
                .collect(Collectors.toMap(Company::getGhlLocationId, Function.identity(), (a, b) -> a));
        return locations.stream()
                .map(l -> toSubAccount(l, enabled.get(l.id())))
                .toList();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public SubAccount enable(String locationId) {
        if (locationId == null || !LOCATION_ID.matcher(locationId).matches()) {
            throw ApiException.badRequest("Invalid GHL sub-account id");
        }
        if (companyRepo.findByGhlLocationId(locationId).isPresent()) {
            throw ApiException.conflict("That sub-account is already enabled");
        }

        TokenGrant token;
        GhlLocation location;
        try {
            token = agency.mintLocationToken(locationId);
            location = agency.getLocation(locationId, token.accessToken());
        } catch (GhlApiException e) {
            throw ApiException.badRequest("GoHighLevel would not open that sub-account. It may not belong to your agency, "
                    + "or the app is missing a scope: " + e.getMessage());
        }

        CompanyResponse company = companyService.create(new CreateCompanyRequest(
                uniqueName(location.name(), locationId), null, null, location.website()));
        companyService.linkGhlLocation(company.id(), locationId);
        connectionService.connectAgencyLocation(company.id(), locationId, token);

        Company saved = companyRepo.findByGhlLocationId(locationId)
                .orElseThrow(() -> new IllegalStateException("Customer vanished right after it was enabled"));
        // Workspace pages are set up after this commits, so a slow or failing tool never undoes the enable.
        events.publishEvent(new CustomerEnabledEvent(saved.getTenantId(), saved.getId(), saved.getName()));
        return toSubAccount(location, saved);
    }

    private SubAccount toSubAccount(GhlLocation l, Company company) {
        PlatformConnection conn = company == null ? null
                : connectionRepo.findFirstByCompanyIdAndPlatform(company.getId(), Platform.GOHIGHLEVEL).orElse(null);
        return new SubAccount(l.id(), l.name(), l.email(), l.phone(), l.website(), l.timezone(), l.city(), l.country(),
                company != null,
                company == null ? null : company.getId(),
                conn == null ? null : conn.getId(),
                conn == null ? null : conn.getHealth().name(),
                conn == null ? null : conn.getLastSyncAt(),
                conn == null ? null : conn.getLastError());
    }

    private String uniqueName(String ghlName, String locationId) {
        String base = ghlName == null || ghlName.isBlank() ? "GHL sub-account " + locationId : ghlName.trim();
        base = base.length() > MAX_NAME ? base.substring(0, MAX_NAME) : base;
        if (!companyRepo.existsByName(base)) {
            return base;
        }
        String suffix = " (" + locationId + ")";
        String head = base.length() + suffix.length() > MAX_NAME ? base.substring(0, MAX_NAME - suffix.length()) : base;
        return head + suffix;
    }
}
