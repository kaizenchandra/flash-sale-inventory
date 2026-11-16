package com.example.flashsale;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class Admission {
    final StringRedisTemplate redis;
    final MeterRegistry metrics;
    final int saleLimit, customerLimit;
    final Semaphore slots = new Semaphore(32);
    final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

    public Admission(StringRedisTemplate redis, MeterRegistry metrics, @Value("${app.admission-limit}") int saleLimit, @Value("${app.customer-limit-per-second}") int customerLimit) {
        this.redis = redis;
        this.metrics = metrics;
        this.saleLimit = saleLimit;
        this.customerLimit = customerLimit;
        script.setLocation(new ClassPathResource("redis/admission.lua"));
        script.setResultType(Long.class);
    }

    public void acquire(UUID sale, String customer) {
        if (!slots.tryAcquire()) throw new Problem.Rejected(429, "Local reservation capacity exhausted");
        try {
            String prefix = "admission:{" + sale + "}:" + (System.currentTimeMillis() / 1000);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(customer.getBytes(StandardCharsets.UTF_8)));
            Long result = redis.execute(script, List.of(prefix, prefix + ":" + digest), String.valueOf(saleLimit), String.valueOf(customerLimit));
            if (result == null || result != 0) {
                metrics.counter("admission.results", "result", "rejected").increment();
                throw new Problem.Rejected(429, "Admission quota exceeded; use bounded jittered backoff");
            }
            metrics.counter("admission.results", "result", "accepted").increment();
        } catch (Problem.Rejected e) {
            slots.release();
            throw e;
        } catch (Exception e) {
            slots.release();
            metrics.counter("admission.results", "result", "unavailable").increment();
            throw new Problem.Rejected(503, "Admission unavailable");
        }
    }

    public void release() {
        slots.release();
    }
}
