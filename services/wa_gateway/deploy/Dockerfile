# Build from the repository root:
#   docker build -f services/wa_gateway/deploy/Dockerfile -t wa-gateway .
#   docker run -d --name wa-gateway -p 127.0.0.1:8787:8787 -v wa-gateway-data:/data wa-gateway
# The port is published on loopback only; put a TLS reverse proxy in front (Caddyfile.example).

FROM rust:1.94-bookworm AS build
WORKDIR /src
COPY . .
RUN cargo build -p wa_gateway --release --locked

FROM debian:bookworm-slim AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin wagateway \
    && mkdir -p /data \
    && chown wagateway:wagateway /data
COPY --from=build /src/target/release/wa_gateway /usr/local/bin/wa_gateway

USER wagateway
ENV WA_GATEWAY_BIND=0.0.0.0:8787 \
    WA_GATEWAY_DATA=/data
VOLUME /data
EXPOSE 8787
# Mount a config file and pass --config to use one; the environment above still wins over it.
ENTRYPOINT ["/usr/local/bin/wa_gateway"]
