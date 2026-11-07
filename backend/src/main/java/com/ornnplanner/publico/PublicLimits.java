package com.ornnplanner.publico;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** The limits of the public version ({@code planner.public.*}); the local app does not read them. */
@Component
@ConfigurationProperties(prefix = "planner.public")
public class PublicLimits {

    /** Calculations a single IP may ask for per minute. */
    private int computePerMinute = 30;
    /** Any request of a single IP per minute (reading included). */
    private int requestsPerMinute = 600;
    /** Most seconds a calculation may take before the visitor is told to try later. */
    private int computeSeconds = 20;
    /** Calculations running at once, for every visitor together. */
    private int workers = 2;
    /** Longest Arena fight, in simulated seconds. */
    private int arenaMaxSeconds = 30;
    /** Effectiveness runs in one request (the screen sends 6 at a time). */
    private int effectJobs = 6;
    /** Origins of other sites allowed to call the API (the page's own origin always is). */
    private List<String> allowedOrigins = new ArrayList<>();

    public int getComputePerMinute() {
        return computePerMinute;
    }

    public void setComputePerMinute(int v) {
        computePerMinute = v;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int v) {
        requestsPerMinute = v;
    }

    public int getComputeSeconds() {
        return computeSeconds;
    }

    public void setComputeSeconds(int v) {
        computeSeconds = v;
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int v) {
        workers = v;
    }

    public int getArenaMaxSeconds() {
        return arenaMaxSeconds;
    }

    public void setArenaMaxSeconds(int v) {
        arenaMaxSeconds = v;
    }

    public int getEffectJobs() {
        return effectJobs;
    }

    public void setEffectJobs(int v) {
        effectJobs = v;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> v) {
        allowedOrigins = v == null ? new ArrayList<>() : v;
    }
}
