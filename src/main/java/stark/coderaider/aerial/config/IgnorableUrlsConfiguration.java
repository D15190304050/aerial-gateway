package stark.coderaider.aerial.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 统一维护“无需认证即可访问”的 URL 列表。
 * 支持：
 * 1. 静态配置（application.yml）
 * 2. 运行时通过 Redis 动态下发（按服务维度）
 * 3. 配置热修改监听
 * 4. 定时兜底刷新
 */
@Data
@ConfigurationProperties(prefix = "authentication")
@Component
@Slf4j
public class IgnorableUrlsConfiguration
{

    /* ---------- 常量 ---------- */
    public static final String IGNORABLE_URLS_KEYS = "ignorable_urls:keys";
    public static final String SERVICE_KEY_PATTERN = "ignorable_urls:service:*";

    /* ---------- 配置 ---------- */
    private List<String> ignorableUrls;          // 静态名单（启动后只读）

    /* ---------- 依赖 ---------- */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisMessageListenerContainer redisMessageListenerContainer;

    /* ---------- 缓存 ---------- */
    // 服务维度原始字符串
    private final Map<String, Set<String>> serviceIgnorableUrlsCache;

    // 编译后的 PathPattern（读写分离，并发安全）
    private volatile List<PathPattern> ignorablePathPatterns;
    private volatile Map<String, PathPattern> pathPatternCache;

    /* ---------- 构造 ---------- */
    public IgnorableUrlsConfiguration()
    {
        serviceIgnorableUrlsCache = new ConcurrentHashMap<>();
        ignorablePathPatterns = List.of();
        pathPatternCache = new ConcurrentHashMap<>();
    }

    /* =======================================
     *  生命周期：订阅 + 初始加载
     * ======================================= */
    @PostConstruct
    public void initSubscriptions()
    {
        try
        {
            // 1. 监听“总 KEY 集合”变更
            redisMessageListenerContainer.addMessageListener(
                (m, p) ->
                {
                    log.info("Received message for ignorable-urls-keys update.");
                    refreshAllConfig();
                },
                new PatternTopic(IGNORABLE_URLS_KEYS));

            // 2. 监听单个服务变更
            redisMessageListenerContainer.addMessageListener(
                (m, p) ->
                {
                    String channel = new String(m.getChannel());
                    log.info("Received message for service update: {}.", channel);
                    refreshServiceConfig(channel);
                    mergeAllIgnorableUrls();
                },
                new PatternTopic(SERVICE_KEY_PATTERN));

            // 3. 首次全量加载
            refreshAllConfig();
        }
        catch (Exception e)
        {
            log.error("Failed to initialize Redis subscriptions.", e);
        }
    }

    /* =======================================
     *  外部触发接口
     * ======================================= */
    public void refreshAllConfig()
    {
        try
        {
            log.info("Refreshing all authentication ignorable URL configurations.");
            Set<String> serviceKeys = stringRedisTemplate.opsForSet().members(IGNORABLE_URLS_KEYS);
            if (serviceKeys != null)
            {
                log.info("Found {} service keys.", serviceKeys.size());
                serviceKeys.forEach(this::refreshServiceConfig);
            }
            else
            {
                log.info("No service keys found.");
            }
            mergeAllIgnorableUrls();
        }
        catch (Exception e)
        {
            log.error("Failed to refresh all authentication configurations.", e);
        }
    }

    /* =======================================
     *  内部：单服务刷新 + 合并
     * ======================================= */
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
                log.info("Key {} no longer exists, remove local cache.", serviceKey);
                serviceIgnorableUrlsCache.remove(serviceKey);
            }
        }
        catch (Exception e)
        {
            log.error("Failed to refresh service config for key: {}.", serviceKey, e);
        }
    }

    /**
     * 合并静态 + 动态名单，原子更新 PathPattern 缓存
     */
    private void mergeAllIgnorableUrls()
    {
        try
        {
            // 1. 收集
            List<String> allIgnorableUrls = new ArrayList<>(ignorableUrls);
            serviceIgnorableUrlsCache.values().forEach(allIgnorableUrls::addAll);

            // 2. 重建（异常隔离）
            Map<String, PathPattern> newCache = new HashMap<>(pathPatternCache);
            newCache.keySet().retainAll(allIgnorableUrls);          // 删掉失效
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

            // 3. 原子发布
            Map<String, PathPattern> oldCache = pathPatternCache;
            pathPatternCache = new ConcurrentHashMap<>(newCache);
            ignorablePathPatterns = List.copyOf(newCache.values());
            oldCache.clear();   // 延迟清理，无并发风险

            log.info("Merged {} valid url patterns into global ignorable list.", newCache.size());
        }
        catch (Exception e)
        {
            log.error("Failed to merge ignorable URLs.", e);
        }
    }

    /* =======================================
     *  热修改监听（静态配置变动）
     * ======================================= */
    @EventListener
    public void onEnvChange(EnvironmentChangeEvent event)
    {
        boolean hit = event.getKeys().stream().anyMatch(k -> k.startsWith("authentication.ignorable-urls"));
        if (hit)
        {
            log.info("Static ignorable-urls changed, reload all.");
            refreshAllConfig();
        }
    }

    /* =======================================
     *  兜底定时刷新
     * ======================================= */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES, initialDelay = 1)
    public void scheduledRefresh()
    {
        log.info("Scheduled refresh of authentication configurations.");
        refreshAllConfig();
    }
}