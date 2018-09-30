package redis.bulk;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisMassInsert {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisMassInsert.class);

    public static void main(String[] args) throws InterruptedException {
        String redisClusterString = args[0];
        String numberOfKeys = args[1];	//number of keys to be inserted from each thread
        String batchSize = args[2];
        String keyHashTagNumber = args[3];
        Duration expirationTime = Duration.parse(args[4]);
        String numberOfThreads = args[5];

        String keyFormat = "{%s}:redis-bulk-import:%s";
        String tenantFormat = "redis-%s-mass-%s";
        String[] tenants = createTenantIDs(Integer.parseInt(keyHashTagNumber), tenantFormat);

        redisMassInsertion(keyFormat, tenants, numberOfKeys, batchSize, redisClusterString, expirationTime, Integer.parseInt(numberOfThreads));
    }

    private static String[] createTenantIDs(int numberOfTenants, String tenantFormat) {
        String randomNumber = Integer.toString(genNumber());

        List<String> tenants = Stream
                .iterate(0, i -> i + 1)
                .limit(numberOfTenants)
                .map(x -> String.format(tenantFormat, randomNumber, Integer.toString(x % numberOfTenants)))
                .collect(toList());

        return tenants.toArray(new String[tenants.size()]);
    }

    private static void redisMassInsertion(String keyFormat, String[] tenants, String numberOfKeys, String batchSize, String redisClusterString, Duration expirationTime, int numberOfThreads) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        Supplier<Boolean> work = () -> executeUsingASyncCommands(keyFormat, tenants, numberOfKeys, batchSize, redisClusterString, expirationTime);

        List<CompletableFuture<Boolean>> completableFutureList = IntStream.rangeClosed(1, numberOfThreads)
                .mapToObj(i-> CompletableFuture.supplyAsync(work))
                .collect(Collectors.toList());

        completableFutureList.stream()
                .map(CompletableFuture::join)	//Wait for the completion of all asynchronous operations.
                .collect(Collectors.toList());

        executor.shutdown();
    }

    /**
     * The normal operation mode of lettuce is to flush every command which means, that every command is written to the transport
     * after it was issued. Why would you want to do this? A flush is an expensive system call and impacts performance. Batching,
     * disabling auto-flushing, can be used under certain conditions and is recommended if:
     * -You perform multiple calls to Redis and you’re not depending immediately on the result of the call
     * -You’re bulk-importing
     * Controlling the flush behavior is only available on the async API. The sync API emulates blocking calls and as soon as
     * you invoke a command, you’re no longer able to interact with the connection until the blocking call ends.
     *  Grouping multiple commands in a batch (size depends on your environment, but batches between 50 and 1000 work nice during
     *  performance tests) can increase the throughput up to a factor of 5x
     * @param tenants
     * @param numberOfKeys
     * @param redisClusterString
     */
    private static boolean executeUsingASyncCommands(String keyFormat, String[] tenants, String numberOfKeys, String batchSize, String redisClusterString, Duration expirationTime) {
        RedisClusterClient clusterClient = getRedisClusterClient(redisClusterString);
        StatefulRedisClusterConnection<String, String> connection = clusterClient.connect();
        RedisAdvancedClusterAsyncCommands<String, String> a_syncCommands = connection.async();

        // disable auto-flushing
        a_syncCommands.setAutoFlushCommands(false);

        long startTime = System.nanoTime();
        for (int i=0; i< Integer.parseInt(numberOfKeys)/Integer.parseInt(batchSize); i++) {
            List<RedisFuture<?>> futures = new ArrayList();
            for(int j=0; j<Integer.parseInt(batchSize); j++) {
                int tenantIndex = j%(tenants.length);
                String key = String.format(keyFormat, tenants[tenantIndex], produceRandomUuid());
                futures.add(a_syncCommands.hset(key, "FirstName", "John"));
                futures.add(a_syncCommands.expire(key, expirationTime.getSeconds()));
            }
            // write all commands to the transport layer
            a_syncCommands.flushCommands();

            // synchronization example: Wait until all futures complete
            LettuceFutures.awaitAll(5, TimeUnit.SECONDS,
                    futures.toArray(new RedisFuture[futures.size()]));
        }

        long stopTime = System.nanoTime();
        Thread thread = Thread.currentThread();
        LOGGER.info("Total time = {} sec from worker {}", (float) (stopTime - startTime) / 1000000000.0, thread.getName());		//to seconds

        return true;
    }

    /**
     * Create a redis cluster client from Redis node string
     * @param redisClusterString
     * @return
     */
    private static RedisClusterClient getRedisClusterClient(String redisClusterString) {
        String[] n = redisClusterString.split(",\\s*");
        List<RedisURI> nodeList = asList(n).stream().map(RedisURI::create).collect(toList());

        RedisClusterClient clusterClient = RedisClusterClient.create(nodeList);

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
                .enableAdaptiveRefreshTrigger(ClusterTopologyRefreshOptions.RefreshTrigger.ASK_REDIRECT, ClusterTopologyRefreshOptions.RefreshTrigger.MOVED_REDIRECT, ClusterTopologyRefreshOptions.RefreshTrigger.PERSISTENT_RECONNECTS)
                .build();

        ClusterClientOptions clientOptions = ClusterClientOptions.builder()
                .topologyRefreshOptions(topologyRefreshOptions)
                .build();

        clusterClient.setOptions(clientOptions);

        return clusterClient;
    }

    private static String produceRandomUuid() {
        return UUID.randomUUID().toString();
    }

    private static int genNumber() {
        Random r = new Random( System.currentTimeMillis() );
        return 10000 + r.nextInt(20000);
    }
}
