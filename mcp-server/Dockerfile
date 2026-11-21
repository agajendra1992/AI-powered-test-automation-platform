# syntax=docker/dockerfile:1
# MCP server image. Build from the repository root:  docker build -f mcp-server/Dockerfile .
# It contains the server jar AND a copy of the framework, because runs are the framework's own
# `mvn test` (JDK, Maven and browsers come from the Playwright Java base image).

# ---- build the shaded server jar (tests run in CI, not during the image build)
FROM mcr.microsoft.com/playwright/java:v1.65.0-noble AS build
WORKDIR /src
COPY config/ config/
COPY mcp-server/pom.xml mcp-server/pom.xml
RUN mvn -B -ntp -q -f mcp-server/pom.xml dependency:go-offline > /dev/null
COPY mcp-server/src mcp-server/src
RUN mvn -B -ntp -q -f mcp-server/pom.xml package -DskipTests

# ---- runtime
FROM mcr.microsoft.com/playwright/java:v1.65.0-noble
USER root
# git: needed only for suggest_regression_tests changed_since
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /work/project /var/lib/qa-mcp /opt/qa-mcp && chown -R pwuser:pwuser /work /var/lib/qa-mcp /opt/qa-mcp
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    MAVEN_ARGS="-B -ntp" \
    QA_MCP_TRANSPORT=http QA_MCP_HTTP_HOST=0.0.0.0 QA_MCP_HTTP_PORT=8765 \
    QA_MCP_PROJECT_DIR=/work/project QA_MCP_STATE_DIR=/var/lib/qa-mcp
USER pwuser
WORKDIR /work/project
# Framework copy: dependencies first (cached), then sources; compiling validates the copy.
COPY --chown=pwuser:pwuser pom.xml ./
COPY --chown=pwuser:pwuser config/ config/
RUN mvn dependency:go-offline -DexcludeReactor=true > /dev/null
COPY --chown=pwuser:pwuser testng-*.xml ./
COPY --chown=pwuser:pwuser src/ src/
RUN mvn -q test-compile
COPY --from=build --chown=pwuser:pwuser /src/mcp-server/target/qa-mcp-server.jar /opt/qa-mcp/qa-mcp-server.jar

EXPOSE 8765
HEALTHCHECK --interval=5s --timeout=3s --retries=30 CMD ["wget", "-qO-", "http://127.0.0.1:8765/health"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=40", "-jar", "/opt/qa-mcp/qa-mcp-server.jar"]
