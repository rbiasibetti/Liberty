package br.com.liberty.security;

import br.com.liberty.infrastructure.security.ApiClientDirectory;
import br.com.liberty.infrastructure.security.LibertySecurityProperties;
import br.com.liberty.infrastructure.security.SigningKeyRing;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Emissão de tokens para sistemas consumidores (client credentials). Os clientes vêm do core, o
 * token é um JWT RS256 de 15 minutos com tenant, ambiente e escopos do cliente, assinado pela chave
 * atual do anel. Nada é guardado por token: a validação é sem estado e a revogação vale pelo status
 * do cliente.
 */
@Configuration(proxyBeanMethods = false)
class AuthorizationServerConfig {

  static final RequestMatcher ENDPOINTS =
      new OrRequestMatcher(
          PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/oauth2/token"),
          PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/oauth2/jwks"),
          PathPatternRequestMatcher.withDefaults()
              .matcher(HttpMethod.GET, "/.well-known/oauth-authorization-server"));

  @Bean
  @Order(1)
  SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher(ENDPOINTS)
        .oauth2AuthorizationServer(Customizer.withDefaults())
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/oauth2/jwks", "/.well-known/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(CsrfConfigurer::disable);
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  AuthorizationServerSettings authorizationServerSettings(LibertySecurityProperties properties) {
    return AuthorizationServerSettings.builder().issuer(properties.issuer().toString()).build();
  }

  @Bean
  RegisteredClientRepository registeredClientRepository(
      ApiClientDirectory directory, LibertySecurityProperties properties) {
    return new RegisteredClientRepository() {
      @Override
      public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Clientes são geridos pela API de administração");
      }

      @Override
      public RegisteredClient findById(String id) {
        return findByClientId(id);
      }

      @Override
      public RegisteredClient findByClientId(String clientId) {
        return directory
            .findFresh(clientId)
            .filter(ApiClientDirectory.ApiClient::usable)
            .map(
                client ->
                    RegisteredClient.withId(client.clientId())
                        .clientId(client.clientId())
                        .clientSecret(client.secretHash())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .scopes(scopes -> scopes.addAll(client.scopes()))
                        .tokenSettings(
                            TokenSettings.builder()
                                .accessTokenTimeToLive(properties.tokenTtl())
                                .build())
                        .build())
            .orElse(null);
      }
    };
  }

  /** Nenhuma autorização é guardada: tokens de cliente são validados sem estado. */
  @Bean
  OAuth2AuthorizationService authorizationService() {
    return new OAuth2AuthorizationService() {
      @Override
      public void save(OAuth2Authorization authorization) {}

      @Override
      public void remove(OAuth2Authorization authorization) {}

      @Override
      public OAuth2Authorization findById(String id) {
        return null;
      }

      @Override
      public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return null;
      }
    };
  }

  /** JWKS publicado: só as partes públicas de todas as chaves em uso (ACTIVE e RETIRING). */
  @Bean
  JWKSource<SecurityContext> jwkSource(SigningKeyRing ring) {
    return (selector, context) -> selector.select(ring.publicJwkSet());
  }

  /**
   * Codificador que assina só com a chave atual, relida a cada token (a rotação vale sem
   * reiniciar).
   */
  @Bean
  JwtEncoder jwtEncoder(SigningKeyRing ring) {
    JWKSource<SecurityContext> signing =
        (selector, context) -> selector.select(ring.signingJwkSet());
    return new NimbusJwtEncoder(signing);
  }

  @Bean
  OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(
      ApiClientDirectory directory, LibertySecurityProperties properties) {
    return context -> {
      if (!AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
        return;
      }
      String clientId = context.getRegisteredClient().getClientId();
      ApiClientDirectory.ApiClient client =
          directory
              .findFresh(clientId)
              .filter(ApiClientDirectory.ApiClient::usable)
              .orElseThrow(
                  () ->
                      new OAuth2AuthenticationException(
                          new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT)));
      Set<String> requested = new LinkedHashSet<>(context.getAuthorizedScopes());
      List<String> granted =
          requested.isEmpty()
              ? List.copyOf(new java.util.TreeSet<>(client.scopes()))
              : List.copyOf(requested);
      context
          .getClaims()
          .audience(List.of(properties.audience()))
          .claim(SecurityConstants.CLAIM_TENANT, client.tenantSlug())
          .claim(SecurityConstants.CLAIM_ENV, client.environment().name())
          .claim(SecurityConstants.CLAIM_CLIENT_ID, client.clientId())
          .claim("scope", granted);
    };
  }

  static Duration defaultTtl() {
    return Duration.ofMinutes(15);
  }
}
