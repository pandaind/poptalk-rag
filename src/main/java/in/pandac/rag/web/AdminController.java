package in.pandac.rag.web;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.support.ScheduledPollConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manual trigger for an immediate ingestion pass, on top of the scheduled
 * poll — useful right after deploying new knowledge files instead of waiting
 * out the poll interval. Guarded by the same PersonaApiKeyAuthFilter as the
 * MCP endpoint (it matches {@code /admin/**}).
 */
@RestController
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final CamelContext camelContext;

    public AdminController(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @PostMapping("/admin/reindex")
    public Map<String, String> reindex() {
        Route route = camelContext.getRoute("knowledge-ingestion");
        if (route == null || !(route.getConsumer() instanceof ScheduledPollConsumer pollConsumer)) {
            log.error("knowledge-ingestion route or its poll consumer not found — cannot trigger reindex");
            return Map.of("status", "error", "message", "Ingestion route not available");
        }

        log.info("Manual reindex triggered");
        pollConsumer.run();
        return Map.of("status", "ok", "message", "Reindex triggered");
    }
}
