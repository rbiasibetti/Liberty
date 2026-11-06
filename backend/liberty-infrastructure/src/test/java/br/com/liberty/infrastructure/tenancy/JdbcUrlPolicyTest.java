package br.com.liberty.infrastructure.tenancy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcUrlPolicyTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:sqlserver://db.cliente.com:1433;databaseName=app;encrypt=true;trustServerCertificate=false",
        "jdbc:sqlserver://10.0.0.5;databaseName=app;encrypt=strict",
        "jdbc:sqlserver://srv-1.local:14330;encrypt=true;"
      })
  void acceptsEncryptedSqlServerUrls(String url) {
    assertThatCode(() -> JdbcUrlPolicy.check(url)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "jdbc:postgresql://h/db",
        "jdbc:mysql://h/db",
        "jdbc:sqlserver://h;databaseName=a",
        "jdbc:sqlserver://h;encrypt=false",
        "jdbc:sqlserver://;encrypt=true",
        "jdbc:sqlserver://h:99999999;encrypt=true",
        "jdbc:sqlserver://h/evil;encrypt=true",
        "jdbc:sqlserver://h;encrypt=true;socketFactoryClass=com.x.Evil",
        "jdbc:sqlserver://h;encrypt=true;SOCKETFACTORYCONSTRUCTORARG=x",
        "jdbc:sqlserver://h;encrypt=true;authenticationScheme=JavaKerberos",
        "jdbc:sqlserver://h;encrypt=true;trustStore=/etc/passwd",
        "jdbc:sqlserver://h;encrypt=true;databaseName=a b;x"
      })
  void rejectsEverythingElse(String url) {
    assertThatThrownBy(() -> JdbcUrlPolicy.check(url))
        .isInstanceOf(TenantUnavailableException.class);
  }
}
