FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B --no-transfer-progress clean package

FROM sapmachine:17.0.17-jre-ubuntu-24.04
WORKDIR /app
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates libvirt0 libvirt-clients openssh-client && \
    update-ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY ./templates/server.xml \
     ./templates/volume.xml \
     ./templates/user-data.yml \
     ./templates/network-config.yml \
     ./templates/meta-data.yml \
     /app/templates/

COPY --from=build /app/target/mnemosyne-*.jar /app/mnemosyne.jar

VOLUME ["/etc/mnemosyne"]

ENTRYPOINT ["java", "-jar", "/app/mnemosyne.jar"]
