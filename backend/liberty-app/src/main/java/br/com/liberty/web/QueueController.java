package br.com.liberty.web;

import br.com.liberty.engine.queue.QueueAdmin;
import br.com.liberty.engine.queue.QueueRepository;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Filas do tenant para usuários (OIDC): VIEWER vê as contagens; PUBLISHER trata as mensagens mortas
 * (devolver à fila ou descartar). O conteúdo das mensagens nunca é exposto.
 */
@RestController
@RequestMapping("/api/v1/tenants/{slug}/queues")
class QueueController {

  private final QueueAdmin admin;

  QueueController(QueueAdmin admin) {
    this.admin = admin;
  }

  @GetMapping
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'VIEWER')")
  List<QueueRepository.Stat> stats(@PathVariable String slug) {
    return admin.stats(slug);
  }

  @GetMapping("/dead")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'PUBLISHER')")
  List<QueueRepository.Dead> dead(@PathVariable String slug) {
    return admin.dead(slug);
  }

  @PostMapping("/dead/{id}/requeue")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'PUBLISHER')")
  ResponseEntity<Void> requeue(
      @PathVariable String slug, @PathVariable long id, Authentication user) {
    admin.requeue(slug, id, user.getName());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/dead/{id}")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'PUBLISHER')")
  ResponseEntity<Void> discard(
      @PathVariable String slug, @PathVariable long id, Authentication user) {
    admin.discard(slug, id, user.getName());
    return ResponseEntity.noContent().build();
  }
}
