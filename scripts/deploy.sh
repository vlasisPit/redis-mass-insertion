#!/bin/bash
cd ..
mvn clean install

DOCKER_USER_ID="vlasispi89"
IMAGE_NAME="redis-mass-insertion"
VERSION="1.0"
TAG="$DOCKER_USER_ID/$IMAGE_NAME:$VERSION"
NUMBER_OF_REPLICAS="2"
SERVICE_NAME="redis-mass-insertion"
NETWORK_NAME="test-net"

# copy executable jar file into docker file and change file format from DOS to Linux
cp target/redis-mass-insertion-1.0-SNAPSHOT-jar-with-dependencies.jar docker/redis-mass-insertion.jar 
sed -i -e 's/\r$//' docker/run.sh

cd docker

# delete service if exists
docker service rm ${IMAGE_NAME}

# build docker image
docker build -t ${TAG} .

docker tag ${IMAGE_NAME} ${TAG}
docker push ${TAG}

# run as a docker container
docker run -e "JAVA_OPTS=-Xms32m -Xmx64m -XX:+UseG1GC -XX:MaxGCPauseMillis=1000 -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 -XX:InitiatingHeapOccupancyPercent=70" ${TAG} redis://192.168.33.10:7000,redis://192.168.33.10:7001,redis://192.168.33.10:7002,redis://192.168.33.10:7003,redis://192.168.33.10:7004,redis://192.168.33.10:7005 10000 1000 5 PT10M 4

# run as a docker service
docker service create --restart-condition none --replicas ${NUMBER_OF_REPLICAS} --detach=true --name ${SERVICE_NAME} --endpoint-mode dnsrr -e 'JAVA_OPTS=-server -Xms32m -Xmx64m -XX:+UseG1GC -XX:MaxGCPauseMillis=1000 -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 -XX:InitiatingHeapOccupancyPercent=70' --network=${NETWORK_NAME} ${TAG} redis://192.168.33.10:7000,redis://192.168.33.10:7001,redis://192.168.33.10:7002,redis://192.168.33.10:7003,redis://192.168.33.10:7004,redis://192.168.33.10:7005 10000 1000 5 PT5M 4

