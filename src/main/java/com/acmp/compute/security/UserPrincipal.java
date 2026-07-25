package com.acmp.compute.security;

import lombok.Builder;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 当前登录用户主体，用于权限校验（含角色与可访问的 resourcePoolIds）。
 */
@Data
@Builder
public class UserPrincipal implements UserDetails {
    private String id;
    private String username;
    private String passwordHash;
    private String role;
    private String organizationId;
    private List<String> resourcePoolIds;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Stream.of("ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }

    public boolean canAccessPool(String poolId) {
        if (Role.PLATFORM_ADMIN.name().equals(role)) {
            return true;
        }
        return resourcePoolIds != null && resourcePoolIds.contains(poolId);
    }
}
