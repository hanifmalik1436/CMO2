package com.cmo2.auth.repository;

import com.cmo2.auth.domain.Role;
import com.cmo2.auth.domain.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(RoleName name);
}
