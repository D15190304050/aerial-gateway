package stark.coderaider.aerial.filters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;
import stark.coderaider.aerial.config.IgnorableUrlsConfiguration;
import stark.coderaider.aerial.services.JwtService;

import java.net.URI;
import java.util.List;

@Slf4j
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered
{
    public static final String REDIRECT_URL = "redirectUrl";
    public static final String SSO_COOKIE_NAME = "titan_gate_login";

    @Autowired
    private JwtService jwtService;

    @Autowired
    private IgnorableUrlsConfiguration ignorableUrlsConfiguration;

    @Value("${titan.treasure.login-url}")
    private String loginPageUrl;

    @Value("${titan.treasure.default-home-url}")
    private String defaultHomeUrl;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain)
    {
        ServerHttpRequest request = exchange.getRequest();
//        ServerHttpResponse response = exchange.getResponse();

        String path = request.getURI().getPath();
        // TODO: Delete this log statement once debugging is done.
        log.info("Request path: {}", path);

        // If it's a whitelisted path, allow it.
        if (isWhitelisted(path))
            return chain.filter(exchange);

        // Get the JWT token from the request cookies.
        String token = getTokenFromRequest(request, SSO_COOKIE_NAME);

        // 如果没有Authorization header，拒绝访问
        if (token == null)
        {
            log.warn("Missing or invalid Authorization header for path: {}", path);
            return redirectToLoginPage(exchange);
        }

        // 验证JWT令牌
        if (!jwtService.tryParseUserInfo(token, request))
        {
            log.warn("Invalid JWT token for path: {}", path);
            return redirectToLoginPage(exchange);
        }

        // 令牌有效，继续处理请求
        return chain.filter(exchange);
    }

    private static String getTokenFromCookies(ServerHttpRequest request, String tokenCookieName)
    {
        if (!StringUtils.hasText(tokenCookieName))
            return null;

        HttpCookie cookie = request.getCookies().getFirst(tokenCookieName);
        return cookie != null ? cookie.getValue() : null;
    }

    private static String getTokenFromHeaders(ServerHttpRequest request)
    {
        return request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    }

    /**
     * Get token from request. Here the token in the header is preferred over that in the cookie.
     * @param request
     * @return
     */
    public static String getTokenFromRequest(ServerHttpRequest request, String tokenCookieName)
    {
        String tokenFromHeaders = getTokenFromHeaders(request);
        return tokenFromHeaders != null ? tokenFromHeaders : getTokenFromCookies(request, tokenCookieName);
    }

    /**
     * 重定向到登录页面
     *
     * @param exchange 当前请求交换机
     * @return Mono&lt;Void>
     */
    private Mono<Void> redirectToLoginPage(ServerWebExchange exchange)
    {
        ServerHttpResponse response = exchange.getResponse();

        // 尝试从Referer头获取前端页面URL
        String referer = exchange.getRequest().getHeaders().getFirst("Referer");
        String redirectUrl = defaultHomeUrl; // 默认使用默认首页地址

        if (StringUtils.hasText(referer))
        {
            // 如果Referer存在且非空，则使用它作为redirectUrl
            redirectUrl = referer;
        }

        // 构造最终的重定向URL，将redirectUrl作为参数传递给登录页面
        String finalRedirectUrl = UriComponentsBuilder.fromUriString(loginPageUrl)
            .queryParam(REDIRECT_URL, redirectUrl)
            .toUriString();

        response.setStatusCode(HttpStatus.FOUND);
        response.getHeaders().setLocation(URI.create(finalRedirectUrl));
        return response.setComplete();
    }

    /**
     * 检查路径是否在白名单中（包括静态配置和动态配置）
     *
     * @param path 请求路径
     * @return 如果在白名单中返回true，否则返回false
     */
    private boolean isWhitelisted(String path)
    {
        List<PathPattern> ignorablePathPatterns = ignorableUrlsConfiguration.getIgnorablePathPatterns();
        if (ignorablePathPatterns == null || ignorablePathPatterns.isEmpty())
            return false;

        return ignorablePathPatterns.stream().anyMatch(pattern -> pattern.matches(requestPathToPathContainer(path)));
    }

    /**
     * 将请求路径转换为PathContainer
     *
     * @param path 请求路径
     * @return PathContainer对象
     */
    private PathContainer requestPathToPathContainer(String path)
    {
        return PathContainer.parsePath(path);
    }

    @Override
    public int getOrder()
    {
        return -100; // 确保在其他过滤器之前执行
    }
}