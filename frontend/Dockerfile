FROM node:25-alpine@sha256:bdf2cca6fe3dabd014ea60163eca3f0f7015fbd5c7ee1b0e9ccb4ced6eb02ef4 AS build
WORKDIR /src
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY . .
RUN npx vite build

FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
COPY --chown=nginx:nginx nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=nginx:nginx /src/dist /usr/share/nginx/html
EXPOSE 8080
USER nginx
