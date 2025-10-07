package stark.coderaider.aerial.config;

import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@Data
@ConfigurationProperties(prefix = "jwt")
@Component
@Slf4j
public class IgnoreUrlsConfig
{
    /**
     * 不需要认证的URL路径列表（静态配置）
     */
    private List<String> ignoreUrls = List.of();

    /**
     * Redis中存储白名单的key
     */
    private String whitelistRedisKey = "jwt:whitelist";

    /**
     * 动态白名单URL路径集合（从Redis中获取）
     */
    private final List<String> dynamicIgnoreUrls = new CopyOnWriteArrayList<>();

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
     * 初始化时从Redis加载白名单
     */
    @PostConstruct
    public void initWhitelistFromRedis()
    {
        loadWhitelistFromRedis();
    }

    /**
     * 从Redis加载白名单
     */
    public void loadWhitelistFromRedis()
    {
        try
        {
            Set<String> whitelist = stringRedisTemplate.opsForSet().members(whitelistRedisKey);
            if (whitelist != null)
            {
                dynamicIgnoreUrls.clear();
                dynamicIgnoreUrls.addAll(whitelist);
            }
        }
        catch (Exception e)
        {
            // 如果Redis不可用，使用静态配置
        }
    }

    /**
     * 获取所有忽略的URL（静态配置 + 动态配置）
     *
     * @return
     */
    public List<String> getAllIgnoreUrls()
    {
        List<String> allUrls = new CopyOnWriteArrayList<>();
        // 添加静态配置的URL
        allUrls.addAll(ignoreUrls);
        // 添加原有动态配置的URL（保持向后兼容）
        allUrls.addAll(dynamicIgnoreUrls);
        // 添加新的服务级动态配置URL
        allUrls.addAll(allIgnoreUrlsCache);
        return allUrls;
    }

    /**
     * 添加URL到Redis白名单
     *
     * @param url
     */
    public void addToWhitelist(String url)
    {
        try
        {
            stringRedisTemplate.opsForSet().add(whitelistRedisKey, url);
            dynamicIgnoreUrls.add(url);
        }
        catch (Exception e)
        {
            // 如果Redis不可用，只添加到内存中
            dynamicIgnoreUrls.add(url);
        }
    }

    /**
     * 从Redis白名单中移除URL
     *
     * @param url
     */
    public void removeFromWhitelist(String url)
    {
        try
        {
            stringRedisTemplate.opsForSet().remove(whitelistRedisKey, url);
            dynamicIgnoreUrls.remove(url);
        }
        catch (Exception e)
        {
            // 如果Redis不可用，只从内存中移除
            dynamicIgnoreUrls.remove(url);
        }
    }

    /**
     * 获取所有忽略的URL（新的服务级动态配置）
     */
    public CopyOnWriteArrayList<String> getAllIgnoreUrlsCache()
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