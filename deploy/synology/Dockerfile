# Teaching customization: build application artifacts in CI, run small containers on the NAS.
FROM eclipse-temurin:17-jre-jammy AS api
WORKDIR /app
COPY playedu-api/playedu-api/target/playedu-api.jar /app/app.jar
EXPOSE 9898
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM nginx:1.28-alpine AS web
COPY playedu-pc/dist /app/pc
COPY playedu-h5/dist /app/h5
COPY playedu-admin/dist /app/admin
COPY deploy/synology/web.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080 8081
