package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes the Agent Credential Broker configuration status to the Angular UI.
 * The frontend uses this to switch between broker mode (links to broker UI)
 * and direct OAuth mode (inline popup flow).
 */
@RestController
@CrossOrigin(origins = "*")
public class BrokerStatusController {

    @Value("${broker.base-url:}")
    private String brokerBaseUrl;

    @Value("${broker.public-url:}")
    private String brokerPublicUrl;

    @GetMapping("/api/broker/status")
    public Map<String, Object> getBrokerStatus() {
        boolean configured = brokerBaseUrl != null && !brokerBaseUrl.isBlank();
        String urlForBrowser = (brokerPublicUrl != null && !brokerPublicUrl.isBlank())
                ? brokerPublicUrl : brokerBaseUrl;
        return Map.of(
                "configured", configured,
                "brokerUrl", configured ? urlForBrowser : ""
        );
    }
}
