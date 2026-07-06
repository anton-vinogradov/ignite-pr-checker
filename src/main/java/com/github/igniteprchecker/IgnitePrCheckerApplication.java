package com.github.igniteprchecker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IgnitePrCheckerApplication {
    public static void main(String[] args) {
        // The box's resolver occasionally blips; cache successful lookups for 5 minutes
        // (JVM default is 30s) so TeamCity calls don't re-resolve on every burst.
        java.security.Security.setProperty("networkaddress.cache.ttl", "300");
        SpringApplication.run(IgnitePrCheckerApplication.class, args);
    }
}
