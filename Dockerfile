# Dockerfile único para os 3 serviços. Uso:
#   docker build --build-arg MODULE=pedido-service -t pedido-service .
# MODULE: pedido-service | estoque-service | api-gateway

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
# Permite build direto pelo Docker Desktop; o Compose sobrescreve esse valor
# para cada serviço (pedido-service, estoque-service ou api-gateway).
ARG MODULE=api-gateway
WORKDIR /workspace
COPY . .
# -am compila também o módulo "common" do qual os serviços dependem
RUN mvn -B -q -pl ${MODULE} -am -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:21-jre
ARG MODULE=api-gateway
WORKDIR /app

# Datadog Java Tracer (APM), versão fixa e alinhada com <dd-trace-api.version> do pom pai
# (a API de spans customizados e o agente devem andar juntos). Para atualizar, troque nos dois lugares.
ARG DD_TRACER_VERSION=1.68.0
ADD https://repo1.maven.org/maven2/com/datadoghq/dd-java-agent/${DD_TRACER_VERSION}/dd-java-agent-${DD_TRACER_VERSION}.jar /opt/datadog/dd-java-agent.jar

COPY --from=build /workspace/${MODULE}/target/${MODULE}-*.jar /app/app.jar

RUN useradd --system --no-create-home appuser \
    && chmod 644 /opt/datadog/dd-java-agent.jar
USER appuser

# O agente é carregado via JAVA_TOOL_OPTIONS; a configuração (DD_SERVICE, DD_ENV,
# DD_AGENT_HOST, DD_LOGS_INJECTION...) vem das variáveis de ambiente do docker-compose.
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/datadog/dd-java-agent.jar"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
