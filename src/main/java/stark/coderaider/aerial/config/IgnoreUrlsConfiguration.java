package stark.coderaider.aerial.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class IgnoreUrlsConfiguration
{
    // ignore-urls-keys的key
    private static final String IGNORE_URLS_KEYS = "ignore_urls:keys";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisMessageListenerContainer redisMessageListenerContainer;

    // 服务级别缓存
    private final Map<String, Set<String>> serviceIgnoreUrlsCache = new ConcurrentHashMap<>();

    // 合并后的完整白名单缓存
    @Getter
    private final CopyOnWriteArrayList<String> allIgnoreUrlsCache = new CopyOnWriteArrayList<>();

    /**
     * 初始化订阅
     */
    @PostConstruct
    public void initSubscriptions()
    {
        try
        {
            // 订阅ignore-urls-keys的变更
            redisMessageListenerContainer.addMessageListener((message, pattern) ->
            {
                log.info("Received message for ignore-urls-keys update");
                refreshAllConfig();
            }, new PatternTopic(IGNORE_URLS_KEYS));

            // 订阅所有服务的ignore_urls:service:*变更
            redisMessageListenerContainer.addMessageListener((message, pattern) ->
            {
                String channel = new String(message.getChannel());
                log.info("Received message for service update: {}", channel);
                // 从频道名提取服务key
                refreshServiceConfig(channel);
            }, new PatternTopic("ignore_urls:service:*"));

            // 初始化加载所有配置
            refreshAllConfig();
        }
        catch (Exception e)
        {
            log.error("Failed to initialize Redis subscriptions", e);
        }
    }

    /**
     * 刷新所有配置
     */
    public void refreshAllConfig()
    {
        try
        {
            log.info("Refreshing all JWT ignore URL configurations");
            // 获取所有服务的key
            Set<String> serviceKeys = stringRedisTemplate.opsForSet().members(IGNORE_URLS_KEYS);
            if (serviceKeys != null)
            {
                log.info("Found {} service keys", serviceKeys.size());
                for (String serviceKey : serviceKeys)
                {
                    refreshServiceConfig(serviceKey);
                }
            }
            else
            {
                log.info("No service keys found");
            }

            // 合并所有服务的白名单
            mergeAllIgnoreUrls();
        }
        catch (Exception e)
        {
            log.error("Failed to refresh all JWT configurations", e);
        }
    }

    /**
     * 刷新单个服务的配置
     */
    private void refreshServiceConfig(String serviceKey)
    {
        try
        {
            log.info("Refreshing service config for key: {}", serviceKey);
            Set<String> urls = stringRedisTemplate.opsForSet().members(serviceKey);
            if (urls != null)
            {
                log.info("Found {} URLs for service key: {}", urls.size(), serviceKey);
                serviceIgnoreUrlsCache.put(serviceKey, urls);
            }
            else
            {
                log.info("No URLs found for service key: {}", serviceKey);
                serviceIgnoreUrlsCache.put(serviceKey, Set.of());
            }
        }
        catch (Exception e)
        {
            log.error("Failed to refresh service config for key: {}", serviceKey, e);
        }
    }

    /**
     * 合并所有服务的白名单
     */
    private void mergeAllIgnoreUrls()
    {
        try
        {
            CopyOnWriteArrayList<String> allUrls = new CopyOnWriteArrayList<>();
            for (Set<String> urls : serviceIgnoreUrlsCache.values())
            {
                allUrls.addAll(urls);
            }
            allIgnoreUrlsCache.clear();
            allIgnoreUrlsCache.addAll(allUrls);
            log.info("Merged {} URLs into global ignore list", allUrls.size());
        }
        catch (Exception e)
        {
            log.error("Failed to merge ignore URLs", e);
        }
    }

    /**
     * 获取所有忽略的URL
     */
    public CopyOnWriteArrayList<String> getAllIgnoreUrls()
    {
        return allIgnoreUrlsCache;
    }

    /**
     * 定期刷新机制作为备份（每5分钟）
     */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void scheduledRefresh()
    {
        log.info("Scheduled refresh of JWT configurations");
        refreshAllConfig();
    }
}