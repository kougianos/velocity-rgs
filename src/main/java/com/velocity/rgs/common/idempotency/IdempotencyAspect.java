package com.velocity.rgs.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.config.PlayerContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Implements the {@link Idempotent} contract:
 * <ul>
 *   <li>requires {@code Idempotency-Key} HTTP header,</li>
 *   <li>hashes the request body via SHA-256,</li>
 *   <li>returns the original response with {@code Idempotent-Replay: true} on hit,</li>
 *   <li>raises {@code IDEMPOTENCY_KEY_CONFLICT} when the same key arrives with a different payload.</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotencyAspect {

    public static final String HEADER_KEY = "Idempotency-Key";
    public static final String HEADER_REPLAY = "Idempotent-Replay";

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<PlayerContext> playerContext;

    @Around("@annotation(com.velocity.rgs.common.idempotency.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attrs.getRequest();
        HttpServletResponse response = attrs.getResponse();

        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Idempotent annotation = method.getAnnotation(Idempotent.class);

        String key = request.getHeader(HEADER_KEY);
        if (key == null || key.isBlank()) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Missing required header '" + HEADER_KEY + "'");
        }

        String scope = resolveScope(annotation.scope(), pjp);
        String payloadHash = computePayloadHash(pjp.getArgs());

        Optional<IdempotencyResult> existing = store.lookup(scope, key);
        if (existing.isPresent()) {
            IdempotencyResult res = existing.get();
            store.assertHashMatches(scope, key, payloadHash, res.payloadHash());
            if (response != null) {
                response.setHeader(HEADER_REPLAY, "true");
            }
            return replayResponse(method, res);
        }

        Object result = pjp.proceed();

        ReplayCapture capture = captureResponse(result);
        store.store(scope, key, payloadHash, capture.statusCode(), capture.body(), annotation.ttlHours());
        if (response != null) {
            response.setHeader(HEADER_REPLAY, "false");
        }
        return result;
    }

    private String resolveScope(String template, ProceedingJoinPoint pjp) {
        String resolved = template;
        if (resolved.contains("{playerId}")) {
            String playerId = Optional.ofNullable(playerContext.getIfAvailable())
                    .map(PlayerContext::getPlayerId)
                    .orElse("anonymous");
            resolved = resolved.replace("{playerId}", playerId);
        }
        if (resolved.contains("{transactionId}")) {
            String tx = findArgumentByName(pjp, "transactionId").orElse("none");
            resolved = resolved.replace("{transactionId}", tx);
        }
        if (resolved.contains("{originalTransactionId}")) {
            String tx = findArgumentByName(pjp, "originalTransactionId").orElse("none");
            resolved = resolved.replace("{originalTransactionId}", tx);
        }
        return resolved;
    }

    private Optional<String> findArgumentByName(ProceedingJoinPoint pjp, String name) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] names = sig.getParameterNames();
        if (names == null) return Optional.empty();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < names.length; i++) {
            if (name.equals(names[i]) && args[i] != null) {
                return Optional.of(args[i].toString());
            }
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) continue;
            try {
                var f = args[i].getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(args[i]);
                if (v != null) return Optional.of(v.toString());
            } catch (ReflectiveOperationException ignored) {
                // try next arg
            }
        }
        return Optional.empty();
    }

    private String computePayloadHash(Object[] args) {
        try {
            String payload = objectMapper.writeValueAsString(args);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (Exception e) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR, "Unable to hash request payload", e);
        }
    }

    private ReplayCapture captureResponse(Object result) {
        try {
            if (result instanceof ResponseEntity<?> re) {
                Object body = re.getBody();
                String json = body == null ? null : objectMapper.writeValueAsString(body);
                return new ReplayCapture(re.getStatusCode().value(), json);
            }
            String json = result == null ? null : objectMapper.writeValueAsString(result);
            return new ReplayCapture(HttpStatus.OK.value(), json);
        } catch (Exception e) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR, "Unable to serialize response for idempotency cache", e);
        }
    }

    private Object replayResponse(Method method, IdempotencyResult res) {
        try {
            Class<?> returnType = method.getReturnType();
            if (ResponseEntity.class.isAssignableFrom(returnType)) {
                Object body = deserializeBody(method, res.responseBody());
                return ResponseEntity.status(res.statusCode()).body(body);
            }
            if (res.responseBody() == null) {
                return null;
            }
            return objectMapper.readValue(res.responseBody(), returnType);
        } catch (Exception e) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR, "Unable to deserialize cached idempotent response", e);
        }
    }

    private Object deserializeBody(Method method, String json) throws Exception {
        if (json == null) return null;
        java.lang.reflect.Type generic = method.getGenericReturnType();
        if (generic instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length == 1) {
                return objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructType(typeArgs[0]));
            }
        }
        return objectMapper.readTree(json);
    }

    private record ReplayCapture(int statusCode, String body) {}
}
