# syntax=docker/dockerfile:1
# Test-runner image: Playwright Java base (JDK + Maven + Chromium/Firefox/WebKit with OS deps).
# The tag must match <playwright.version> in pom.xml. CI enforces this (scripts/check_versions.py),
# so a Dependabot Playwright PR fails until this line is updated in the same PR.
FROM mcr.microsoft.com/playwright/java:v1.65.0-noble

# Browsers are already in /ms-playwright; never download them at test time.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    MAVEN_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75" \
    MAVEN_ARGS="-B -ntp"

WORKDIR /work
RUN chown pwuser:pwuser /work
USER pwuser

# 1) Dependencies and plugins first: this layer is reused until pom.xml changes.
COPY --chown=pwuser:pwuser pom.xml ./
COPY --chown=pwuser:pwuser config/ config/
RUN mvn dependency:go-offline -DexcludeReactor=true > /dev/null

# 2) Sources. Compiling here (with the Checkstyle gate) fails the image build on broken code.
COPY --chown=pwuser:pwuser testng-*.xml ./
COPY --chown=pwuser:pwuser src/ src/
RUN mvn test-compile -q

# Results, reports and failure evidence are written to /work/target (mount it to keep them).
ENTRYPOINT ["mvn", "test"]
CMD ["-DsuiteXmlFile=testng-smoke.xml"]
