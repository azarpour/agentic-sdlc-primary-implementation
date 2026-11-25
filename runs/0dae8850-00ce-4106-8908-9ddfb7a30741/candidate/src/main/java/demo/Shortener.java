package demo;

import java.net.URI;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Shortener {

    public static void main(String[] args) {
        SpringApplication.run(Shortener.class, args);
    }

    static URI validate(String url) {
        return UrlValidator.validate(url);
    }
}
