#!/bin/bash
cd ..
mvn clean install

DOCKER_USER_ID="vlasispi89"
IMAGE_NAME="redis-mass-insertion"
TAG="$DOCKER_USER_ID/$IMAGE_NAME"
NUMBER_OF_REPLICAS="2"
SERVICE_NAME="redis-mass-insertion"

# copy executable jar file into docker file
cp target/redis-mass-insertion-1.0-SNAPSHOT-jar-with-dependencies.jar docker/redis-mass-insertion.jar 

cd docker

# build docker image
docker build -t ${TAG} .

docker tag ${IMAGE_NAME} ${TAG}
docker push ${TAG}

# run as a docker container
docker run -e "JAVA_OPTS=-Xms32m -Xmx64m -XX:+UseG1GC -XX:MaxGCPauseMillis=1000 -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 -XX:InitiatingHeapOccupancyPercent=70" ${IMAGE_NAME} redis://192.168.33.10:7000,redis://192.168.33.10:7001,redis://192.168.33.10:7002,redis://192.168.33.10:7003,redis://192.168.33.10:7004,redis://192.168.33.10:7005 10000 1000 5 PT10M 4

# run as a docker service
docker service create --restart-condition none --replicas ${NUMBER_OF_REPLICAS} --detach=true --name ${SERVICE_NAME} --endpoint-mode dnsrr -e docker_component=redis-mass-insertion -e 'JAVA_OPTS=-server -Xms32m -Xmx64m -XX:+UseG1GC -XX:MaxGCPauseMillis=1000 -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 -XX:InitiatingHeapOccupancyPercent=70' --network=${network name} ${IMAGE_NAME} redis://192.168.33.10:7000,redis://192.168.33.10:7001,redis://192.168.33.10:7002,redis://192.168.33.10:7003,redis://192.168.33.10:7004,redis://192.168.33.10:7005 10000 1000 5 PT5M 4

