package in.pandac.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PoptalkRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(PoptalkRagApplication.class, args);
    }
}
