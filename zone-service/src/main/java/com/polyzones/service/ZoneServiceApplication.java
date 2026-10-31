package com.polyzones.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZoneServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZoneServiceApplication.class, args);
    }
}
