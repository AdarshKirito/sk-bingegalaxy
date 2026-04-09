FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm install --no-audit --no-fund
COPY . .
ARG VITE_GOOGLE_CLIENT_ID
ENV VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID
RUN npm run build

FROM nginx:alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && chown -R appuser:appgroup /usr/share/nginx/html /var/cache/nginx /var/log/nginx \
    && touch /var/run/nginx.pid && chown appuser:appgroup /var/run/nginx.pid
COPY --from=build --chown=appuser:appgroup /app/dist /usr/share/nginx/html
COPY --chown=appuser:appgroup nginx.conf /etc/nginx/conf.d/default.conf
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --spider -q http://127.0.0.1:8080/ || exit 1
CMD ["nginx", "-g", "daemon off;"]
