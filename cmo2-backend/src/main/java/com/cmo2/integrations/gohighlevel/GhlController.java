package com.cmo2.integrations.gohighlevel;

import com.cmo2.integrations.gohighlevel.GhlAgencyService.AgencyStatus;
import com.cmo2.integrations.gohighlevel.GhlCustomerService.SubAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The owner's GHL agency connection and customer list. The OAuth redirect lands on an
 * authenticated CMO² page, which posts the code here — so the tenant always comes from the
 * signed-in user, never from anything GHL echoes back.
 */
@RestController
@RequestMapping("/api/ghl")
@Tag(name = "GoHighLevel agency")
public class GhlController {

    private final GhlAgencyService agency;
    private final GhlCustomerService customers;

    public GhlController(GhlAgencyService agency, GhlCustomerService customers) {
        this.agency = agency;
        this.customers = customers;
    }

    public record ConnectAgencyRequest(@NotBlank @Size(max = 4096) String code) {}

    @GetMapping("/agency")
    @Operation(summary = "Whether the GHL app is configured and the agency is connected")
    public AgencyStatus status() {
        return agency.status();
    }

    @PostMapping("/agency/connect")
    @Operation(summary = "Complete the agency install: exchange the code GHL sent back for agency tokens")
    public AgencyStatus connect(@Valid @RequestBody ConnectAgencyRequest request) {
        return agency.connect(request.code());
    }

    @DeleteMapping("/agency")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disconnect the GHL agency")
    public void disconnect() {
        agency.disconnect();
    }

    @GetMapping("/sub-accounts")
    @Operation(summary = "Every sub-account under the agency, and which are enabled as customers")
    public List<SubAccount> subAccounts() {
        return customers.listSubAccounts();
    }

    @PostMapping("/sub-accounts/{locationId}/enable")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enable a sub-account as a customer: creates the company and connects it through the agency")
    public SubAccount enable(@PathVariable String locationId) {
        return customers.enable(locationId);
    }
}
