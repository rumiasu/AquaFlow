# Draft: build only after review and approval. Reuses the existing prod profile.
# Supply a reviewed Java 17 Linux JRE image pinned by digest; no default image/pull.
ARG JRE_IMAGE
FROM ${JRE_IMAGE}
WORKDIR /tmp
# 2026-10-15: COPY --chmod=0644 can create a non-traversable parent.
# Create the application directory explicitly; file readability alone is insufficient.
RUN install -d -m 0755 /opt/aquaflow
COPY --chmod=0644 app.jar /opt/aquaflow/app.jar
COPY --chmod=0644 entrypoint.sh /opt/aquaflow/entrypoint.sh
ENV SPRING_PROFILES_ACTIVE=prod \
    PAYMENT_TEST_MODE=false \
    DEV_LOGIN_ENABLED=false \
    MOCK_WECHAT_PAY=false \
    SERVER_FORWARD_HEADERS_STRATEGY=none \
    SPRING_SQL_INIT_MODE=never
USER 10001:10001
# Fail the build if the actual runtime user cannot traverse/read the launch chain.
RUN test -x /opt && test -x /opt/aquaflow \
    && test -r /opt/aquaflow/entrypoint.sh && test -r /opt/aquaflow/app.jar \
    && /bin/sh -n /opt/aquaflow/entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/opt/aquaflow/entrypoint.sh"]
