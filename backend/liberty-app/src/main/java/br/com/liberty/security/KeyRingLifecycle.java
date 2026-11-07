package br.com.liberty.security;

import br.com.liberty.infrastructure.security.SigningKeyRing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Garante a chave de assinatura na subida (sem ela não há como emitir tokens, então falhar é
 * correto) e a mantém atualizada: todas as instâncias recarregam a cada 5 minutos e tentam
 * rotacionar uma vez por dia.
 */
@Component
class KeyRingLifecycle implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(KeyRingLifecycle.class);

  private final SigningKeyRing ring;

  KeyRingLifecycle(SigningKeyRing ring) {
    this.ring = ring;
  }

  @Override
  public void afterSingletonsInstantiated() {
    ring.rotateIfDue();
  }

  @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
  void refresh() {
    try {
      ring.refresh();
    } catch (RuntimeException e) {
      LOG.error("Falha ao recarregar as chaves de assinatura; mantendo as atuais", e);
    }
  }

  @Scheduled(cron = "0 17 3 * * *")
  void rotate() {
    try {
      ring.rotateIfDue();
    } catch (RuntimeException e) {
      LOG.error("Falha ao rotacionar as chaves de assinatura", e);
    }
  }
}
