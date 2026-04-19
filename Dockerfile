FROM eclipse-temurin:21-jdk as build
WORKDIR application
COPY . .
RUN ./gradlew installDist

FROM eclipse-temurin:21-jre
WORKDIR application
RUN apt-get update && apt-get install -y procps
COPY --from=build application/build/install/org.openmbee.flexo.mms.layer1/ .
ENTRYPOINT ["./bin/org.openmbee.flexo.mms.layer1"]
EXPOSE 8080
