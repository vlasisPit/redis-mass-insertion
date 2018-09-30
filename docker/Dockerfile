# Alpine Linux with OpenJDK JRE
FROM openjdk:8-jre-alpine

WORKDIR /var/redis-mass-insertion
ADD redis-mass-insertion.jar run.sh ./
ENTRYPOINT [ "./run.sh", "redis-mass-insertion.jar" ]