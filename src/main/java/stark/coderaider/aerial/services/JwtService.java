package stark.coderaider.aerial.services;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class JwtService
{
    public static final String USER_ID = "user_id";
    public static final String USERNAME = "username";
    public static final String NICKNAME = "nickname";

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_NAME = "X-User-Name";
    public static final String HEADER_USER_NICKNAME = "X-User-Nickname";

    @Value("${titan.gate.jwt-secret}")
    public String secret;

    private JWTVerifier verifier;

    @PostConstruct
    public void initialize()
    {
        Algorithm jwtAlgorithm = Algorithm.HMAC256(secret);
        verifier = JWT.require(jwtAlgorithm).build();
    }

    /**
     * 尝试从JWT令牌中解析用户信息并设置到请求头中
     *
     * @param token JWT令牌
     * @param request 当前请求
     * @return 如果成功解析用户信息并设置到请求头中返回true，否则返回false
     */
    public boolean tryParseUserInfo(String token, ServerHttpRequest request)
    {
        try
        {
            DecodedJWT decodedJwt = verifier.verify(token);

            Long userId = decodedJwt.getClaim(USER_ID).asLong();
            String username = decodedJwt.getClaim(USERNAME).asString();
            String nickname = decodedJwt.getClaim(NICKNAME).asString();

            if (userId != null && username != null)
            {
                request.getHeaders().put(HEADER_USER_ID, List.of(userId.toString()));
                request.getHeaders().put(HEADER_USER_NAME, List.of(username));
                request.getHeaders().put(HEADER_USER_NICKNAME, List.of(nickname));

                return true;
            }

            return false;
        }
        catch (Exception e)
        {
            log.error("Failed to parse user info from JWT token.", e);
            return false;
        }
    }

}
