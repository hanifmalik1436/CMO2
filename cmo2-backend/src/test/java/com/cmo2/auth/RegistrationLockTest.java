package com.cmo2.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** On an owner's console, self-registration closes once an account exists. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "cmo2.auth.self-registration=first-user-only")
class RegistrationLockTest {

    @Autowired
    MockMvc mvc;

    private String body() {
        return """
                {"email":"lock.%s@example.com","password":"superSecret123","fullName":"X","organizationName":"Org"}
                """.formatted(UUID.randomUUID());
    }

    @Test
    void onlyTheFirstAccountCanSelfRegister() throws Exception {
        // The in-memory test DB is shared across test classes, so the first attempt may or may
        // not be the very first account; the second attempt is always refused.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body()));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
    }
}
