FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -B package
FROM eclipse-temurin:21-jre
COPY --from=build /src/target/shortener-1.0.0.jar /app.jar
USER 10001
ENTRYPOINT ["java","-jar","/app.jar"]
