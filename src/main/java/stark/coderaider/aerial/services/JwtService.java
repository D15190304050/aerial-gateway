package stark.coderaider.aerial.services;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService
{
    @Value("${titan.gate.jwt-secret}")
    public String secret;

    private JWTVerifier verifier;

    public boolean verify(String token)
    {
        try
        {
            verifier.verify(token);
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    @PostConstruct
    public void initialize()
    {
        Algorithm jwtAlgorithm = Algorithm.HMAC256(secret);
        verifier = JWT.require(jwtAlgorithm).build();
    }
}
