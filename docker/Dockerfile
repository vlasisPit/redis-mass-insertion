# Alpine Linux with OpenJDK JRE
FROM openjdk:8-jre-alpine

WORKDIR /var/redis-mass-insertion
ADD redis-mass-insertion.jar logback.xml run.sh ./
ENTRYPOINT [ "sh", "./run.sh", "redis-mass-insertion.jar" ]