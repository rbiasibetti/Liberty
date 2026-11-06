# Imagem do backend: o jar é construído e testado antes (CI), aqui só é empacotado.
# Base distroless sem shell, executando como usuário não-root.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:a90a5fadd78dc37b643da72dca2f744ef67eba625eca7227a5b375b548e6b1c2

ARG JAR_FILE=liberty-app/target/liberty-app.jar
COPY --chown=nonroot:nonroot ${JAR_FILE} /app/liberty-app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
USER nonroot
CMD ["-jar", "/app/liberty-app.jar"]
