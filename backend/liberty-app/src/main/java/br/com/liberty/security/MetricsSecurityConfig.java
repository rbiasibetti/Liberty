package br.com.liberty.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Métricas do Prometheus (as etiquetas revelam tenants e integrações) só com HTTP Basic de uma
 * conta técnica própria. Sem {@code liberty.metrics.password} configurada o endpoint fica fechado
 * para todos; a senha vem do ambiente (cofre/Secret), nunca do repositório.
 */
@Configuration(proxyBeanMethods = false)
class MetricsSecurityConfig {

  @Bean
  @Order(0)
  SecurityFilterChain metricsFilterChain(
      HttpSecurity http,
      @Value("${liberty.metrics.username:metrics}") String username,
      @Value("${liberty.metrics.password:}") String password)
      throws Exception {
    http.securityMatcher("/actuator/prometheus")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
    if (password.isBlank()) {
      http.authorizeHttpRequests(r -> r.anyRequest().denyAll());
    } else {
      var users =
          new InMemoryUserDetailsManager(
              User.withUsername(username)
                  .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password))
                  .roles("METRICS")
                  .build());
      http.userDetailsService(users)
          .httpBasic(Customizer.withDefaults())
          .authorizeHttpRequests(r -> r.anyRequest().hasRole("METRICS"));
    }
    return http.build();
  }
}
