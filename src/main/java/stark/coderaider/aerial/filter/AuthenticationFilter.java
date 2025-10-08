package stark.coderaider.aerial.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import stark.coderaider.aerial.config.IgnoreUrlsConfiguration;
import stark.coderaider.aerial.services.JwtService;

import java.util.List;

@Slf4j
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered
{
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    @Autowired
    private JwtService jwtService;

    @Autowired
    private IgnoreUrlsConfiguration ignoreUrlsConfiguration;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain)
    {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        String path = request.getURI().getPath();
        log.debug("Processing request: {}", path);

        // 检查是否在白名单中（包括静态配置和动态配置）
        if (isWhitelisted(path))
        {
            log.debug("Path {} is whitelisted, allowing access", path);
            return chain.filter(exchange);
        }

        // 从Header中获取Authorization
        String token = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        // 如果没有Authorization header，拒绝访问
        if (token == null)
        {
            log.warn("Missing or invalid Authorization header for path: {}", path);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }

        // 验证JWT令牌
        if (!jwtService.verify(token))
        {
            log.warn("Invalid JWT token for path: {}", path);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }

        // 令牌有效，继续处理请求
        log.debug("JWT token validated successfully for path: {}", path);
        return chain.filter(exchange);
    }

    /**
     * 检查路径是否在白名单中（包括静态配置和动态配置）
     *
     * @param path 请求路径
     * @return 如果在白名单中返回true，否则返回false
     */
    private boolean isWhitelisted(String path)
    {
        List<String> ignoreUrls = ignoreUrlsConfiguration.getAllIgnoreUrls();
        if (ignoreUrls == null || ignoreUrls.isEmpty())
            return false;

        return ignoreUrls.stream().anyMatch(pattern ->
            antPathMatcher.match(pattern, path));
    }

    @Override
    public int getOrder()
    {
        return -100; // 确保在其他过滤器之前执行
    }
}