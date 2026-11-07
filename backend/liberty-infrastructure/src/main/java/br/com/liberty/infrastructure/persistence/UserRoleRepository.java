package br.com.liberty.infrastructure.persistence;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface UserRoleRepository extends Repository<UserRoleEntity, UserRoleEntity.Key> {

  List<UserRoleEntity> findByIdUserSubAndIdTenantId(String userSub, Long tenantId);

  List<UserRoleEntity> findByIdUserSub(String userSub);

  List<UserRoleEntity> findByIdTenantId(Long tenantId);

  java.util.Optional<UserRoleEntity> findById(UserRoleEntity.Key key);

  UserRoleEntity save(UserRoleEntity role);

  void delete(UserRoleEntity role);
}
