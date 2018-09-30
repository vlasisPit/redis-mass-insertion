# Description

This is a Redis Mass insertion application. You can bulk import some keys in Redis, alongside expiration time in order to be deleted automatically after the time interval you specified as an argument has elapsed. You can add some hash keys in Redis using the "hset" command. Lettuce is used as Redis Java client. This is multi-thread application, which can run as a docker container or a docker service.

Application argument's are the following:

* redisClusterString (args[0]): Redis cluster string in the following form. Each connection string must have the form `redis :// host [: port]`.
Cluster connection string argument must consist of such connection strings delimeted by `,`.
Example ` redis://127.0.0.1:7000,redis://127.0.0.1:7001,redis://127.0.0.1:7002 `
	
* numberOfKeys (args[1]): Number of keys to be inserted from each thread.

* batchSize (args[2]): Batching is used in order to accelarate the insertion of data into Redis. Under normal conditions, Lettuce executes commands as soon as they are called by an API client. This is what most normal applications want, especially if they rely on receiving command results serially.
However, this behavior isn’t efficient if applications don’t need results immediately or if large amounts of data are being uploaded in bulk. Asynchronous applications can override this behavior.
Grouping multiple commands in a batch (size depends on your environment, but batches between 50 and 1000 work nice during performance tests) can increase the throughput up to a factor of 5x.

* keyHashTagNumber (args[3]): Number of how many different key hash tags will be used.
Key hash tags are used in order to allocate the keys in the nodes of the cluster.

* expirationTime = (args[4]) Time interval after the key in Redis will expire. The time must be on PT5M form.
This is for 5 minutes.

* numberOfThreads = (args[5]) Number of threads to execute in parallel Redis bulk import.

