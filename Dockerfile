# syntax=docker/dockerfile:1
#
# Builds Traccar directly from source (this fork), instead of downloading the
# official pre-built release zip. Meant for EasyPanel (or any Git-based
# build) pointed at this repository.
#
# The web UI now runs as its own service (VFPAR/traccar-web, nginx). This
# image no longer ships the UI: /web only holds a page that redirects old
# bookmarks to WEB_PUBLIC_URL. The API (/api) keeps working here as before.
# traccar-web is still cloned (not built) because the server needs its
# translations for notification templates.

ARG WEB_REPO=https://github.com/VFPAR/traccar-web.git
ARG WEB_REF=feat/micodus-integration
ARG WEB_PUBLIC_URL=http://traccar-web.ouoljf.easypanel.host

# ---- Stage 1: build the Java server (this repo) ----
FROM eclipse-temurin:25 AS server-build
WORKDIR /src
COPY . .
RUN chmod +x ./gradlew && ./gradlew assemble --no-daemon

# ---- Stage 2: fetch traccar-web translations only (no UI build) ----
FROM alpine/git AS web-src
ARG WEB_REPO
ARG WEB_REF
WORKDIR /src
RUN git clone --branch "$WEB_REF" --depth 1 "$WEB_REPO" .

# ---- Stage 2b: redirect page for the old embedded UI (React + Tailwind) ----
FROM node:22-alpine AS web-redirect
ARG WEB_PUBLIC_URL
WORKDIR /app
COPY docker/web-redirect/package.json docker/web-redirect/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY docker/web-redirect/ ./
RUN VITE_WEB_PUBLIC_URL="${WEB_PUBLIC_URL%/}" npm run build

# ---- Stage 3: stage the release payload (mirrors the official out/ layout) ----
FROM alpine AS stage
WORKDIR /out
COPY --from=server-build /src/target/tracker-server.jar ./
COPY --from=server-build /src/target/lib/ ./lib/
COPY --from=server-build /src/schema/ ./schema/
COPY --from=server-build /src/templates/ ./templates/
COPY --from=server-build /src/setup/traccar.xml ./conf/traccar.xml
COPY --from=web-redirect /app/dist/ ./web/
COPY --from=web-src /src/src/resources/l10n/ ./templates/translations/
RUN mkdir -p ./data ./logs

# ---- Stage 4: slim JRE (same as docker/Dockerfile.alpine) ----
FROM eclipse-temurin:25-alpine AS jdk
RUN jlink --add-modules java.se,jdk.charsets,jdk.crypto.ec,jdk.net,jdk.unsupported \
    --strip-debug --no-header-files --no-man-pages --compress=2 --output /jre

# ---- Stage 5: final runtime image ----
FROM alpine
COPY --from=stage /out /opt/traccar
COPY --from=jdk /jre /opt/traccar/jre
WORKDIR /opt/traccar
EXPOSE 8082
VOLUME ["/opt/traccar/data", "/opt/traccar/logs"]
ENTRYPOINT ["/opt/traccar/jre/bin/java", "-XX:+ExitOnOutOfMemoryError"]
CMD ["-jar", "tracker-server.jar", "conf/traccar.xml"]
