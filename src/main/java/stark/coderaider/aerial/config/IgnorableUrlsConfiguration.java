package stark.coderaider.aerial.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Data
@ConfigurationProperties(prefix = "authentication")
@Component
@Slf4j
public class IgnorableUrlsConfiguration
{
    /**
     * ignorable-urls-keys的key.
     */
    private static final String IGNORABLE_URLS_KEYS = "ignorable_urls:keys";

    /**
     * 服务级别缓存的key的匹配模式
     */
    public static final String SERVICE_KEY_PATTERN = "ignorable_urls:service:*";

    /**
     * 不需要认证的URL路径列表（静态配置）
     */
    private List<String> ignorableUrls;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisMessageListenerContainer redisMessageListenerContainer;

    // 服务级别缓存
    private final Map<String, Set<String>> serviceIgnorableUrlsCache;

    private volatile List<PathPattern> ignorablePathPatterns;

    private final HashMap<String, PathPattern> pathPatternCache;

    public IgnorableUrlsConfiguration()
    {
        serviceIgnorableUrlsCache = new ConcurrentHashMap<>();
        ignorablePathPatterns = new ArrayList<>();
        pathPatternCache = new HashMap<>();
    }

    /**
     * 初始化订阅
     */
    @PostConstruct
    public void initSubscriptions()
    {
        try
        {
            // 订阅ignorable-urls-keys的变更
            redisMessageListenerContainer.addMessageListener((message, pattern) ->
            {
                log.info("Received message for ignorable-urls-keys update.");
                refreshAllConfig();
            }, new PatternTopic(IGNORABLE_URLS_KEYS));

            // 订阅所有服务的ignorable_urls:service:*变更
            redisMessageListenerContainer.addMessageListener((message, pattern) ->
            {
                String channel = new String(message.getChannel());
                log.info("Received message for service update: {}.", channel);

                // We can merge all ignorable URLs here because normally we won't update ignorable URLs of multiple
                // services at the same time.
                refreshServiceConfig(channel);
                mergeAllIgnorableUrls();
            }, new PatternTopic(SERVICE_KEY_PATTERN));

            // 初始化加载所有配置
            refreshAllConfig();
        }
        catch (Exception e)
        {
            log.error("Failed to initialize Redis subscriptions.", e);
        }
    }

    /**
     * 刷新所有配置
     */
    public void refreshAllConfig()
    {
        try
        {
            log.info("Refreshing all authentication ignorable URL configurations.");
            // 获取所有服务的key
            Set<String> serviceKeys = stringRedisTemplate.opsForSet().members(IGNORABLE_URLS_KEYS);
            if (serviceKeys != null)
            {
                log.info("Found {} service keys.", serviceKeys.size());
                for (String serviceKey : serviceKeys)
                    refreshServiceConfig(serviceKey);
            }
            else
                log.info("No service keys found");

            // 合并所有服务的白名单
            mergeAllIgnorableUrls();
        }
        catch (Exception e)
        {
            log.error("Failed to refresh all authentication configurations.", e);
        }
    }

    /**
     * 刷新单个服务的配置
     */
    private void refreshServiceConfig(String serviceKey)
    {
        try
        {
            log.info("Refreshing service config for key: {}.", serviceKey);
            Set<String> urls = stringRedisTemplate.opsForSet().members(serviceKey);
            if (urls != null)
            {
                log.info("Found {} URLs for service key: {}.", urls.size(), serviceKey);
                serviceIgnorableUrlsCache.put(serviceKey, urls);
            }
            else
            {
                log.info("No URLs found for service key: {}.", serviceKey);
                serviceIgnorableUrlsCache.put(serviceKey, Set.of());
            }
        }
        catch (Exception e)
        {
            log.error("Failed to refresh service config for key: {}.", serviceKey, e);
        }
    }

    /**
     * 合并所有服务的白名单，并原子地更新到内存缓存
     */
    private void mergeAllIgnorableUrls()
    {
        try
        {
            // 1. 收集：静态配置 + 所有服务动态下发的 URL
            List<String> allIgnorableUrls = new ArrayList<>(ignorableUrls); // 静态
            serviceIgnorableUrlsCache.values().forEach(allIgnorableUrls::addAll); // 动态

            // 2. 重建：只保留仍有效的 Pattern，新增未缓存的 Pattern
            Map<String, PathPattern> newCache = new HashMap<>(pathPatternCache);
            // 2.1 删除已失效的 Pattern
            newCache.keySet().retainAll(allIgnorableUrls);

            // 2.2 新增或补全 Pattern（异常隔离，单条失败不影响整体）
            for (String url : allIgnorableUrls)
            {
                if (!newCache.containsKey(url))
                {
                    try
                    {
                        PathPattern pattern = PathPatternParser.defaultInstance.parse(url);
                        newCache.put(url, pattern);
                    }
                    catch (Exception e)
                    {
                        log.warn("Invalid url pattern skipped: {}", url, e);
                    }
                }
            }

            // 3. 原子发布：一次性替换缓存和只读列表
            pathPatternCache.clear();
            pathPatternCache.putAll(newCache);
            ignorablePathPatterns = List.copyOf(newCache.values()); // 不可变，安全并发

            log.info("Merged {} valid url patterns into global ignorable list.", newCache.size());
        }
        catch (Exception e)
        {
            log.error("Failed to merge ignorable URLs.", e);
        }
    }

    /**
     * 定期刷新机制作为备份（每1分钟）
     */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void scheduledRefresh()
    {
        log.info("Scheduled refresh of authentication configurations.");
        refreshAllConfig();
    }
}