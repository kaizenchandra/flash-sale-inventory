package com.example.flashsale;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

@Configuration
public class Security {
    @Bean
    @Profile("!demo")
    JwtDecoder decoder(@Value("${app.issuer}") String issuer, @Value("${app.audience}") String audience) {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), jwt -> jwt.getAudience().contains(audience) ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return decoder;
    }

    @Bean
    @Profile("!demo")
    SecurityFilterChain production(HttpSecurity http) throws Exception {
        JwtGrantedAuthoritiesConverter grants = new JwtGrantedAuthoritiesConverter();
        grants.setAuthoritiesClaimName("roles");
        grants.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grants);
        return common(http).oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))).build();
    }

    @Bean
    @Profile("demo")
    SecurityFilterChain demo(HttpSecurity http) throws Exception {
        return common(http).httpBasic(b -> {
        }).build();
    }

    HttpSecurity common(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS)).authorizeHttpRequests(a -> a.requestMatchers("/", "/index.html", "/actuator/health/**").permitAll().requestMatchers("/api/v1/admin/**", "/actuator/prometheus").hasRole("ADMIN").requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/flash-sales/**", "/api/v1/inventory/**").hasRole("ADMIN").anyRequest().authenticated());
    }

    @Bean
    @Profile("demo")
    org.springframework.security.core.userdetails.UserDetailsService users() {
        var encoder = org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new org.springframework.security.provisioning.InMemoryUserDetailsManager(org.springframework.security.core.userdetails.User.withUsername("customer").password(encoder.encode("customer-local")).roles("CUSTOMER").build(), org.springframework.security.core.userdetails.User.withUsername("admin").password(encoder.encode("admin-local")).roles("ADMIN", "CUSTOMER").build());
    }
}
