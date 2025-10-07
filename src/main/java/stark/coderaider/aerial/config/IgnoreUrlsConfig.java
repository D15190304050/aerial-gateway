package stark.coderaider.aerial.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@ConfigurationProperties(prefix = "jwt")
@Component
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

    @Autowired
    private IgnoreUrlsConfiguration ignoreUrlsConfiguration;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
        allUrls.addAll(ignoreUrlsConfiguration.getAllIgnoreUrls());
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
}