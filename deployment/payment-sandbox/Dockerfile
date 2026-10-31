# Independent payment test image; never replace deployment/limited-trial or the release entry.
ARG JRE_IMAGE
FROM ${JRE_IMAGE}
WORKDIR /tmp
RUN install -d -m 0755 /opt/aquaflow
COPY --chmod=0644 app.jar /opt/aquaflow/app.jar
COPY --chmod=0644 entrypoint.sh /opt/aquaflow/entrypoint.sh
ENV SPRING_PROFILES_ACTIVE=payment-sandbox \
    MOCK_WECHAT_PAY=false \
    DEV_LOGIN_ENABLED=false \
    SERVER_FORWARD_HEADERS_STRATEGY=none \
    SPRING_SQL_INIT_MODE=never
USER 10001:10001
RUN test -x /opt/aquaflow && test -r /opt/aquaflow/app.jar \
    && test -r /opt/aquaflow/entrypoint.sh && /bin/sh -n /opt/aquaflow/entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "/opt/aquaflow/entrypoint.sh"]
