package com.cmo2.signup;

import com.cmo2.signup.SignupDtos.SignupRequest;
import com.cmo2.signup.SignupDtos.SignupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated signup. Registered under /api/signup (permitAll in
 * {@link com.cmo2.shared.config.SecurityConfig}, and — unlike /api/webhooks/** — still
 * subject to {@code RateLimitFilter} since it accepts arbitrary public input and triggers
 * real provisioning).
 */
@RestController
@RequestMapping("/api/signup")
@Tag(name = "Signup")
public class SignupController {

    private final SignupService signupService;

    public SignupController(SignupService signupService) {
        this.signupService = signupService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Public signup: provisions a GoHighLevel sub-account, no CMO2 login issued")
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return signupService.signup(request);
    }
}
