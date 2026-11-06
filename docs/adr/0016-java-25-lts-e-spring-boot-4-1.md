# 0016. Java 25 LTS e Spring Boot 4.1

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: T1

## Contexto

A proposta citava Java 21 e Spring Boot 3.x; em novembro de 2026 a linha 3.5 já saiu do suporte aberto e o JDK instalado é o 25.

## Decisão

Build e execução em Java 25 (LTS), Spring Boot 4.1.x, Maven 3.9 pelo wrapper, google-java-format 1.35 via Spotless. Substitui as versões citadas na proposta v2.

## Consequências

Bibliotecas devem declarar suporte a Java 25 e Spring Framework 7. O Authorization Server vem do Spring Security (etapa E08). O JaCoCo exige 80% de cobertura de linhas por módulo; mutação (PIT) roda no perfil `mutation`.

## Alternativas descartadas

Java 21 + Spring Boot 3.5 (sem suporte aberto).
