# Imagem do backend: o jar é construído e testado antes (CI), aqui só é empacotado.
# Base distroless sem shell, executando como usuário não-root.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:a90a5fadd78dc37b643da72dca2f744ef67eba625eca7227a5b375b548e6b1c2

ARG JAR_FILE=liberty-app/target/liberty-app.jar
ARG WORKER_LIB=liberty-scripts/target/worker-lib
COPY --chown=nonroot:nonroot ${JAR_FILE} /app/liberty-app.jar
# Classpath do trabalhador de script: um jar executável do Spring Boot não serve como -cp
COPY --chown=nonroot:nonroot ${WORKER_LIB} /app/worker-lib

ENV LIBERTY_SCRIPTS_WORKER_CLASSPATH="/app/worker-lib/*"
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
USER nonroot
CMD ["-jar", "/app/liberty-app.jar"]
