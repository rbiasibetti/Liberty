package br.com.liberty.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface IdempotencyRepository
    extends Repository<IdempotencyEntity, IdempotencyEntity.Key> {

  Optional<IdempotencyEntity> findById(IdempotencyEntity.Key key);

  IdempotencyEntity save(IdempotencyEntity entry);

  long deleteByExpiresAtBefore(Instant limit);
}
