package io.github.lakernote.meerkat.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MeerkatExampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeerkatExampleApplication.class, args);
    }
}
