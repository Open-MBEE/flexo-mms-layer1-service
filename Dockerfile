FROM openjdk:17.0.2-jdk-slim as build
WORKDIR application
COPY . .
RUN cp -rf $JAVA_HOME/lib/security/cacerts ./src/main/resources/ && \
    openssl req -newkey rsa:2048 -x509 -keyout key.pem -out certificate.pem -days 3650 -passout pass:changeit -subj "/C=US/ST=California/L=Pasadena/O=NASA Jet Propulsion Laboratory/OU=ITSD/CN=mms.openmbee.org" -addext "subjectAltName = DNS:mms.openmbee.org" && \
    openssl pkcs12 -export -in certificate.pem -inkey key.pem -out identity.p12 -name "mms.openmbee.org" -passin pass:changeit -passout pass:changeit && \
    keytool -importkeystore -destkeystore ./src/main/resources/keystore.jks -deststorepass changeit -srckeystore identity.p12 -srcstoretype PKCS12 -srcstorepass changeit && \
    keytool -importcert -noprompt -alias mms.openmbee.org -file certificate.pem -keypass changeit -keystore ./src/main/resources/cacerts -storepass changeit -storetype JKS && \
    ./gradlew --no-daemon installDist

FROM openjdk:17.0.2-jdk-slim
WORKDIR application
RUN apt-get update && apt-get install -y procps
COPY --from=build application/build/install/org.openmbee.flexo.mms.layer1/ .
COPY --from=build application/src/main/resources/keystore.jks /application/keystore.jks
COPY --from=build application/src/main/resources/cacerts /application/cacerts
ENTRYPOINT ["./bin/org.openmbee.flexo.mms.layer1"]
EXPOSE 8080
