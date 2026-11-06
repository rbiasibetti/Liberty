package br.com.liberty.infrastructure.persistence;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface UserRoleRepository extends Repository<UserRoleEntity, UserRoleEntity.Key> {

  List<UserRoleEntity> findByIdUserSubAndIdTenantId(String userSub, Long tenantId);

  UserRoleEntity save(UserRoleEntity role);

  void delete(UserRoleEntity role);
}
