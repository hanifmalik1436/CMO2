package com.cmo2.auth.domain;

import com.cmo2.auth.repository.RoleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Idempotently ensures all five RBAC roles exist. In production Flyway already
 * seeds them (this finds them present and does nothing); under the test profile,
 * where Flyway is off and Hibernate builds the schema, this populates the table.
 *
 * <p>Runs early ({@code @Order(10)}) so later runners (AgentSeeder, the demo seeder)
 * can rely on the roles being present.
 */
@Component
@Order(10)
public class RoleSeeder implements ApplicationRunner {

    private final RoleRepository roleRepository;

    public RoleSeeder(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (RoleName name : RoleName.values()) {
            if (roleRepository.findByName(name).isEmpty()) {
                Role role = new Role();
                role.setName(name);
                roleRepository.save(role);
            }
        }
    }
}
