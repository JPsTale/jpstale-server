package org.jpstale.server.login;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.jpstale")
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
