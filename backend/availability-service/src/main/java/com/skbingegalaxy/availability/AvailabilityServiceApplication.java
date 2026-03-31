package com.skbingegalaxy.availability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {
    "com.skbingegalaxy.availability",
    "com.skbingegalaxy.common.exception"
})
@EnableDiscoveryClient
public class AvailabilityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AvailabilityServiceApplication.class, args);
    }
}
