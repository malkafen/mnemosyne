FROM maven:4.0.0-rc-5-amazoncorretto-17-debian-trixie AS build

WORKDIR /app
COPY . .
RUN mvn clean package
RUN ls

FROM sapmachine:17.0.17-ubuntu-24.04
WORKDIR /app
RUN apt update && apt install -y ca-certificates && update-ca-certificates
RUN apt install -y libvirt0 libvirt-clients libvirt-dev openssh-client
COPY ./templates /app/templates
COPY --from=build /app/target/mnemosyne-*.jar .
COPY ./configs/logback.xml /app/configs/logback.xml