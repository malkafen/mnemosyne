FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn clean package

FROM sapmachine:17.0.17-ubuntu-24.04
WORKDIR /app
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates libvirt0 libvirt-clients libvirt-dev openssh-client && \
    update-ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY ./templates/server.xml \
     ./templates/volume.xml \
     ./templates/user-data.yml \
     ./templates/network-config.yml \
     /app/templates/
COPY ./configs/logback.xml /app/configs/logback.xml
COPY --from=build /app/target/mnemosyne-*.jar /app/mnemosyne.jar

VOLUME ["/etc/mnemosyne"]

ENTRYPOINT ["java", "-Dlogback.configurationFile=/app/configs/logback.xml", \
            "-jar", "/app/mnemosyne.jar"]
