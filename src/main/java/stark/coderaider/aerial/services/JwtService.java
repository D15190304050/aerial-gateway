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
     * 解析 JWT 并把用户信息写到一个 新的 ServerHttpRequest 上。
     *
     * @param token  原始 jwt
     * @param request 原始请求（只读）
     * @return 如果解析成功，返回携带用户头的新请求；否则返回null
     */
    public ServerHttpRequest tryParseUserInfo(String token, ServerHttpRequest request)
    {
        try
        {
            DecodedJWT decodedJwt = verifier.verify(token);

            Long userId = decodedJwt.getClaim(USER_ID).asLong();
            String username = decodedJwt.getClaim(USERNAME).asString();
            String nickname = decodedJwt.getClaim(NICKNAME).asString();

            if (userId != null && username != null)
            {
                return request.mutate()
                    .header(HEADER_USER_ID, userId.toString())
                    .header(HEADER_USER_NAME, username)
                    .header(HEADER_USER_NICKNAME, nickname == null ? "" : nickname)
                    .build();
            }
        }
        catch (Exception e)
        {
            log.error("Failed to parse user info from JWT token", e);
        }

        return null;
    }
}
