# Draft: build only after review and approval. Reuses the existing prod profile.
# Supply a reviewed Java 17 Linux JRE image pinned by digest; no default image/pull.
ARG JRE_IMAGE
FROM ${JRE_IMAGE}
WORKDIR /tmp
COPY app.jar /opt/aquaflow/app.jar
COPY entrypoint.sh /opt/aquaflow/entrypoint.sh
ENV SPRING_PROFILES_ACTIVE=prod \
    DEV_LOGIN_ENABLED=false \
    MOCK_WECHAT_PAY=false \
    SERVER_FORWARD_HEADERS_STRATEGY=none \
    SPRING_SQL_INIT_MODE=never
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/opt/aquaflow/entrypoint.sh"]
