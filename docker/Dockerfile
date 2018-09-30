# Alpine Linux with OpenJDK JRE
FROM openjdk:8-jre-alpine

WORKDIR /var/redis-mass-insertion
COPY ../target/redis-mass-insertion-1.0-SNAPSHOT-jar-with-dependencies.jar redis-mass-insertion.jar
ADD redis-mass-insertion.jar run.sh ./
ENTRYPOINT [ "./run.sh", "redis-mass-insertion.jar" ]