package org.dinesh.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to check and respect robots.txt rules
 */
public class RobotsTxtChecker {
    private static final Logger logger = Logger.getLogger(RobotsTxtChecker.class.getName());
    private static final Pattern USER_AGENT_PATTERN = Pattern.compile("^User-agent:\\s*(\\*|.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISALLOW_PATTERN = Pattern.compile("^Disallow:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLOW_PATTERN = Pattern.compile("^Allow:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    /**
     * Check if a URL is allowed by robots.txt
     * @param targetUrl The URL to check
     * @param userAgent The user agent to check against (use "*" for all)
     * @return true if allowed, false if disallowed or error occurs
     */
    public static boolean isUrlAllowed(String targetUrl, String userAgent) {
        try {
            URL url = new URL(targetUrl);
            String robotsUrl = url.getProtocol() + "://" + url.getHost() + "/robots.txt";
            
            URLConnection connection = new URL(robotsUrl).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            Set<String> disallowedPaths = new HashSet<>();
            Set<String> allowedPaths = new HashSet<>();
            boolean relevantSection = false;
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                Matcher userAgentMatcher = USER_AGENT_PATTERN.matcher(line);
                if (userAgentMatcher.matches()) {
                    String agent = userAgentMatcher.group(1).trim().toLowerCase();
                    if (agent.equals("*") || agent.equals(userAgent.toLowerCase())) {
                        relevantSection = true;
                    } else {
                        relevantSection = false;
                    }
                    continue;
                }
                
                if (relevantSection) {
                    Matcher disallowMatcher = DISALLOW_PATTERN.matcher(line);
                    if (disallowMatcher.matches()) {
                        String path = disallowMatcher.group(1).trim();
                        disallowedPaths.add(path);
                        continue;
                    }
                    
                    Matcher allowMatcher = ALLOW_PATTERN.matcher(line);
                    if (allowMatcher.matches()) {
                        String path = allowMatcher.group(1).trim();
                        allowedPaths.add(path);
                        continue;
                    }
                }
            }
            reader.close();
            
            // Check if the specific path is disallowed
            String path = url.getPath();
            if (path.isEmpty()) {
                path = "/";
            }
            
            // Check explicit allows first (they override disallows)
            for (String allowPath : allowedPaths) {
                if (pathMatches(path, allowPath)) {
                    logger.fine("robots.txt explicitly allows: " + targetUrl);
                    return true;
                }
            }
            
            // Check disallows
            for (String disallowPath : disallowedPaths) {
                if (pathMatches(path, disallowPath)) {
                    logger.warning("robots.txt DISALLOWS: " + targetUrl + " (matched: " + disallowPath + ")");
                    return false;
                }
            }
            
            logger.fine("robots.txt allows (no matching rules): " + targetUrl);
            return true;
            
        } catch (Exception e) {
            logger.warning("Could not check robots.txt for " + targetUrl + ": " + e.getMessage());
            // If we can't check robots.txt, we should be conservative and allow
            // but log the issue
            return true;
        }
    }
    
    private static boolean pathMatches(String path, String rule) {
        if (rule.equals("/") || rule.equals("/*")) {
            return true;
        }
        if (rule.isEmpty()) {
            return false;
        }
        if (rule.endsWith("*")) {
            String prefix = rule.substring(0, rule.length() - 1);
            return path.startsWith(prefix);
        }
        return path.equals(rule) || path.startsWith(rule);
    }
    
    /**
     * Log a warning if robots.txt checking is disabled
     */
    public static void logComplianceWarning() {
        if (!org.dinesh.config.Config.isCheckRobotsTxt()) {
            logger.warning("robots.txt checking is DISABLED. This may violate website terms of service.");
        }
        if (!org.dinesh.config.Config.isRespectRobotsTxt()) {
            logger.warning("robots.txt compliance is DISABLED. This may violate website terms of service.");
        }
    }
}