FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build

WORKDIR /workspace
COPY pom.xml checkstyle.xml ./
COPY infra/rocketmq/probe/pom.xml infra/rocketmq/probe/pom.xml
COPY infra/rocketmq/probe/src infra/rocketmq/probe/src
RUN mvn --batch-mode --no-transfer-progress --file infra/rocketmq/probe/pom.xml package

FROM eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd

WORKDIR /opt/citybuddy
COPY --from=build /workspace/infra/rocketmq/probe/target/rocketmq-probe.jar ./rocketmq-probe.jar
COPY --chmod=755 infra/rocketmq/probe/probe-entrypoint.sh ./probe-entrypoint.sh

ENTRYPOINT ["/opt/citybuddy/probe-entrypoint.sh"]
CMD ["serve"]
