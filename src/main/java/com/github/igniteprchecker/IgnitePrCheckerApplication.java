package com.github.igniteprchecker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IgnitePrCheckerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IgnitePrCheckerApplication.class, args);
    }
}
