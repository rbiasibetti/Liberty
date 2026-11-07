package br.com.liberty.security;

import br.com.liberty.infrastructure.security.ApiClientDirectory;
import br.com.liberty.infrastructure.security.LibertySecurityProperties;
import br.com.liberty.infrastructure.security.SigningKeyRing;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * API protegida: tokens de dois emissores (os de cliente, validados com as chaves locais; os de
 * usuário, com o provedor OIDC), sem sessão, sem cookies, negando por padrão tudo que não estiver
 * listado.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class ResourceServerConfig {

  @Bean
  @Order(2)
  SecurityFilterChain apiFilterChain(
      HttpSecurity http,
      AuthenticationManagerResolver<jakarta.servlet.http.HttpServletRequest> resolver,
      ProblemSecurityHandlers problems,
      @Qualifier("corsConfigurationSource") CorsConfigurationSource cors)
      throws Exception {
    http.csrf(CsrfConfigurer::disable)
        .cors(c -> c.configurationSource(cors))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .oauth2ResourceServer(
            rs ->
                rs.authenticationManagerResolver(resolver)
                    .authenticationEntryPoint(problems)
                    .accessDeniedHandler(problems))
        .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                    .referrerPolicy(
                        r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/*/api/**")
                    .hasRole("CLIENT")
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(LibertySecurityProperties properties) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(properties.allowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(
        List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key"));
    config.setAllowCredentials(false);
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  /** Escolhe o validador pelo emissor do token; emissor desconhecido nunca autentica. */
  @Bean
  AuthenticationManagerResolver<jakarta.servlet.http.HttpServletRequest>
      authenticationManagerResolver(
          SigningKeyRing ring,
          ApiClientDirectory directory,
          LibertySecurityProperties properties,
          JWKSource<SecurityContext> publicKeys) {
    AuthenticationManager clientTokens =
        manager(clientDecoder(publicKeys, directory, properties), clientConverter());
    AuthenticationManager userTokens =
        properties.oidc().issuer() == null
            ? null
            : manager(
                new SupplierJwtDecoder(() -> userDecoder(properties)),
                userConverter(properties.oidc().platformAdminRole()));
    Map<String, AuthenticationManager> byIssuer = new java.util.HashMap<>();
    byIssuer.put(properties.issuer().toString(), clientTokens);
    if (userTokens != null) {
      byIssuer.put(properties.oidc().issuer().toString(), userTokens);
    }
    return new org.springframework.security.oauth2.server.resource.authentication
        .JwtIssuerAuthenticationManagerResolver(
        (AuthenticationManagerResolver<String>) issuer -> byIssuer.get(issuer));
  }

  private static AuthenticationManager manager(
      JwtDecoder decoder, JwtAuthenticationConverter converter) {
    JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
    provider.setJwtAuthenticationConverter(converter);
    return provider::authenticate;
  }

  private static JwtDecoder clientDecoder(
      JWKSource<SecurityContext> publicKeys,
      ApiClientDirectory directory,
      LibertySecurityProperties properties) {
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, publicKeys));
    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.issuer().toString()),
            audience(properties.audience()),
            new ClientTokenValidator(directory)));
    return decoder;
  }

  private static JwtDecoder userDecoder(LibertySecurityProperties properties) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withIssuerLocation(properties.oidc().issuer().toString()).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.oidc().issuer().toString()),
            authorizedParty(properties.oidc().clientId()),
            hasSubject()));
    return decoder;
  }

  private static OAuth2TokenValidator<Jwt> audience(String expected) {
    return jwt ->
        jwt.getAudience() != null && jwt.getAudience().contains(expected)
            ? OAuth2TokenValidatorResult.success()
            : failure("Audiência inválida");
  }

  /** Token de usuário precisa ter sido emitido para o cliente da interface (claim azp). */
  private static OAuth2TokenValidator<Jwt> authorizedParty(String clientId) {
    return jwt ->
        clientId.equals(jwt.getClaimAsString("azp"))
            ? OAuth2TokenValidatorResult.success()
            : failure("Token não emitido para a interface");
  }

  private static OAuth2TokenValidator<Jwt> hasSubject() {
    return jwt ->
        jwt.getSubject() == null || jwt.getSubject().isBlank()
            ? failure("Token sem sujeito")
            : OAuth2TokenValidatorResult.success();
  }

  private static OAuth2TokenValidatorResult failure(String description) {
    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
  }

  /**
   * Token de cliente: revogado, expirado ou de tenant inativo deixa de valer em até o TTL do cache.
   */
  private record ClientTokenValidator(ApiClientDirectory directory)
      implements OAuth2TokenValidator<Jwt> {
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
      String clientId = jwt.getClaimAsString(SecurityConstants.CLAIM_CLIENT_ID);
      if (clientId == null) {
        return failure("Token sem cliente");
      }
      return directory
          .findCached(clientId)
          .filter(ApiClientDirectory.ApiClient::usable)
          .filter(c -> c.tenantSlug().equals(jwt.getClaimAsString(SecurityConstants.CLAIM_TENANT)))
          .filter(
              c -> c.environment().name().equals(jwt.getClaimAsString(SecurityConstants.CLAIM_ENV)))
          .map(c -> OAuth2TokenValidatorResult.success())
          .orElseGet(() -> failure("Cliente revogado, expirado ou inconsistente"));
    }
  }

  /** Só o emissor local produz ROLE_CLIENT: o papel depende de quem assinou, nunca de um claim. */
  private static JwtAuthenticationConverter clientConverter() {
    JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          List<GrantedAuthority> authorities = new ArrayList<>();
          authorities.add(new SimpleGrantedAuthority(SecurityConstants.ROLE_CLIENT));
          authorities.addAll(scopes.convert(jwt));
          return authorities;
        });
    return converter;
  }

  /**
   * Usuários do OIDC: ROLE_USER; o administrador da plataforma vem de um papel do provedor, nunca
   * do banco.
   */
  private static JwtAuthenticationConverter userConverter(String platformAdminRole) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          List<GrantedAuthority> authorities = new ArrayList<>();
          authorities.add(new SimpleGrantedAuthority(SecurityConstants.ROLE_USER));
          if (jwt.getClaim("realm_access") instanceof Map<?, ?> realm
              && realm.get("roles") instanceof Collection<?> roles
              && roles.contains(platformAdminRole)) {
            authorities.add(new SimpleGrantedAuthority(SecurityConstants.ROLE_PLATFORM_ADMIN));
          }
          return authorities;
        });
    return converter;
  }
}
