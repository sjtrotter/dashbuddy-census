# syntax=docker/dockerfile:1
# The edge image (#1178): upstream Caddy plus caddy-ratelimit, so per-IP limits can run at the edge with the
# client address held ONLY in the plugin's memory — no access log, no retention (ADR-0011). Built and published
# by .github/workflows/caddy-image.yml; Compose pins the published tag. Pins are bumped deliberately.
# TODO: pin both base images by digest via Dependabot (as for the census image).
FROM caddy:2.11.6-builder AS builder
RUN xcaddy build \
    --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM caddy:2.11.6-alpine
LABEL org.opencontainers.image.source="https://github.com/sjtrotter/dashbuddy-census" \
      org.opencontainers.image.description="dashbuddy-census edge: Caddy + caddy-ratelimit (per-IP limits held in memory only)"
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
