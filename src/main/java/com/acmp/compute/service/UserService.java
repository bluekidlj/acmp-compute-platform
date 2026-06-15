package com.acmp.compute.service;

import com.acmp.compute.dto.LoginResponse;
import com.acmp.compute.entity.User;
import com.acmp.compute.mapper.UserMapper;
import com.acmp.compute.security.JwtTokenProvider;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
        return UserPrincipal.builder()
                .id(user.getId())
                .username(user.getUsername())
                .passwordHash(user.getPasswordHash())
                .role(user.getRole())
                .organizationId(user.getOrganizationId())
                .resourcePoolIds(List.of())
                .build();
    }

    public LoginResponse login(String username, String password) {
        UserPrincipal principal = (UserPrincipal) loadUserByUsername(username);
        if (!passwordEncoder.matches(password, principal.getPassword())) {
            throw new org.springframework.security.authentication.BadCredentialsException("密码错误");
        }
        String token = jwtTokenProvider.generateToken(
                principal.getId(),
                principal.getUsername(),
                principal.getRole(),
                List.of()
        );
        return LoginResponse.builder()
                .token(token)
                .username(principal.getUsername())
                .role(principal.getRole())
                .expiresInMs(jwtTokenProvider.getExpirationMs())
                .build();
    }
}
