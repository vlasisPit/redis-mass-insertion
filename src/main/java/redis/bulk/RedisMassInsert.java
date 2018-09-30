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
        String keyHashTagFormat = "redis-%s-keyHashTag-%s";
        String[] keyHashTags = createKeyHashTags(Integer.parseInt(keyHashTagNumber), keyHashTagFormat);

        redisMassInsertion(keyFormat, keyHashTags, numberOfKeys, batchSize, redisClusterString, expirationTime, Integer.parseInt(numberOfThreads));
    }

    /**
     * Hash tags are a way to ensure that multiple keys are allocated in the same hash slot. This is used in order to implement multi-key operations
     * in Redis Cluster. In order to implement hash tags, the hash slot for a key is computed in a slightly different way in certain conditions. If
     * the key contains a "{...}" pattern only the substring between { and } is hashed in order to obtain the hash slot. However since it is possible
     * that there are multiple occurrences of { or } the algorithm is well specified by the following rules:     *
     * -IF the key contains a { character.
     * -AND IF there is a } character to the right of {
     * -AND IF there are one or more characters between the first occurrence of { and the first occurrence of }.
     * Then instead of hashing the key, only what is between the first occurrence of { and the following first occurrence of } is hashed.
     * @param keyHashTagNumber
     * @param keyHashTagFormat
     * @return
     */
    private static String[] createKeyHashTags(int keyHashTagNumber, String keyHashTagFormat) {
        String randomNumber = Integer.toString(genNumber());

        List<String> keyHashTags = Stream
                .iterate(0, i -> i + 1)
                .limit(keyHashTagNumber)
                .map(x -> String.format(keyHashTagFormat, randomNumber, Integer.toString(x % keyHashTagNumber)))
                .collect(toList());

        return keyHashTags.toArray(new String[keyHashTags.size()]);
    }

    private static void redisMassInsertion(String keyFormat, String[] keyHashTags, String numberOfKeys, String batchSize, String redisClusterString, Duration expirationTime, int numberOfThreads) {
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        Supplier<Boolean> work = () -> executeUsingASyncCommands(keyFormat, keyHashTags, numberOfKeys, batchSize, redisClusterString, expirationTime);

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
     * @param keyHashTags
     * @param numberOfKeys
     * @param redisClusterString
     */
    private static boolean executeUsingASyncCommands(String keyFormat, String[] keyHashTags, String numberOfKeys, String batchSize, String redisClusterString, Duration expirationTime) {
        RedisClusterClient clusterClient = getRedisClusterClient(redisClusterString);
        StatefulRedisClusterConnection<String, String> connection = clusterClient.connect();
        RedisAdvancedClusterAsyncCommands<String, String> a_syncCommands = connection.async();

        // disable auto-flushing
        a_syncCommands.setAutoFlushCommands(false);

        long startTime = System.nanoTime();
        for (int i=0; i< Integer.parseInt(numberOfKeys)/Integer.parseInt(batchSize); i++) {
            List<RedisFuture<?>> futures = new ArrayList();
            for(int j=0; j<Integer.parseInt(batchSize); j++) {
                int keyHashTagsIndex = j%(keyHashTags.length);
                String key = String.format(keyFormat, keyHashTags[keyHashTagsIndex], produceRandomUuid());
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
