package com.cmo2;

import com.cmo2.shared.config.Cmo2Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(Cmo2Properties.class)
@EnableScheduling
public class Cmo2Application {

    public static void main(String[] args) {
        SpringApplication.run(Cmo2Application.class, args);
    }
}
