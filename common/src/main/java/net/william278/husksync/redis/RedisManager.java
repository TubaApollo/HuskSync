package net.william278.husksync.redis;

import net.william278.husksync.config.Settings;
import net.william278.husksync.data.UserData;
import net.william278.husksync.player.User;
import org.jetbrains.annotations.NotNull;
import org.xerial.snappy.Snappy;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages the connection to the Redis server, handling the caching of user data
 */
public class RedisManager {

    private static final String KEY_NAMESPACE = "husksync:";
    private static String clusterId = "";

    private final JedisPoolConfig jedisPoolConfig;

    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final boolean redisUseSsl;

    private JedisPool jedisPool;

    public RedisManager(@NotNull Settings settings) {
        clusterId = settings.getStringValue(Settings.ConfigOption.CLUSTER_ID);
        this.redisHost = settings.getStringValue(Settings.ConfigOption.REDIS_HOST);
        this.redisPort = settings.getIntegerValue(Settings.ConfigOption.REDIS_PORT);
        this.redisPassword = settings.getStringValue(Settings.ConfigOption.REDIS_PASSWORD);
        this.redisUseSsl = settings.getBooleanValue(Settings.ConfigOption.REDIS_USE_SSL);

        // Configure the jedis pool
        this.jedisPoolConfig = new JedisPoolConfig();
        this.jedisPoolConfig.setMaxIdle(0);
        this.jedisPoolConfig.setTestOnBorrow(true);
        this.jedisPoolConfig.setTestOnReturn(true);
    }

    /**
     * Initialize the redis connection pool
     *
     * @return a future returning void when complete
     */
    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            if (redisPassword.isBlank()) {
                jedisPool = new JedisPool(jedisPoolConfig, redisHost, redisPort, 0, redisUseSsl);
            } else {
                jedisPool = new JedisPool(jedisPoolConfig, redisHost, redisPort, 0, redisPassword, redisUseSsl);
            }
            try {
                jedisPool.getResource().ping();
            } catch (JedisException e) {
                return false;
            }
            return true;
        });
    }

    /**
     * Set a user's data to the Redis server
     *
     * @param user         the user to set data for
     * @param userData     the user's data to set
     * @param redisKeyType the type of key to set the data with. This determines the time to live for the data.
     * @return a future returning void when complete
     */
    public CompletableFuture<Void> setUserData(@NotNull User user, @NotNull UserData userData,
                                               @NotNull RedisKeyType redisKeyType) {
        try {
            return CompletableFuture.runAsync(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    // Set the user's data as a compressed byte array of the json using Snappy
                    jedis.setex(getKey(redisKeyType, user.uuid), redisKeyType.timeToLive,
                            Snappy.compress(userData.toJson().getBytes(StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Fetch a user's data from the Redis server
     *
     * @param user         The user to fetch data for
     * @param redisKeyType The type of key to fetch
     * @return The user's data, if it's present on the database. Otherwise, an empty optional.
     */
    public CompletableFuture<Optional<UserData>> getUserData(@NotNull User user,
                                                             @NotNull RedisKeyType redisKeyType) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                final byte[] compressedJson = jedis.get(getKey(redisKeyType, user.uuid));
                if (compressedJson == null) {
                    return Optional.empty();
                }
                // Use Snappy to decompress the json
                return Optional.of(UserData.fromJson(new String(Snappy.uncompress(compressedJson),
                        StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void close() {
        if (jedisPool != null) {
            if (!jedisPool.isClosed()) {
                jedisPool.close();
            }
        }
    }

    private static byte[] getKey(@NotNull RedisKeyType keyType, @NotNull UUID uuid) {
        return (keyType.getKeyPrefix() + ":" + uuid).getBytes(StandardCharsets.UTF_8);
    }

    public enum RedisKeyType {
        CACHE(60 * 60 * 24),
        SERVER_CHANGE(2);

        public final int timeToLive;

        RedisKeyType(int timeToLive) {
            this.timeToLive = timeToLive;
        }

        @NotNull
        public String getKeyPrefix() {
            return KEY_NAMESPACE.toLowerCase() + ":" + clusterId.toLowerCase() + ":" + name().toLowerCase();
        }
    }

}