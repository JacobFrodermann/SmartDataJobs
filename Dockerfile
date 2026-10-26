# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY libs ./libs
COPY src ./src
RUN mvn -B -ntp clean package

# Small artifact image containing the deployable WAR. The master Payara image
# (Dockerfile.payara) copies /opt/wars/SmartDataJobs.war from this image.
FROM alpine:3.20 AS artifact
COPY --from=build /workspace/target/SmartDataJobs.war /opt/wars/SmartDataJobs.war
