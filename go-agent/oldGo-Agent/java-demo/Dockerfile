# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY src/HelloServer.java .
RUN javac HelloServer.java \
    && jar --create --file /out/app.jar --main-class HelloServer HelloServer.class

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /out/app.jar /app/app.jar
ENV SERVER_PORT=8080
ENV SERVICE_NAME=java-demo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
