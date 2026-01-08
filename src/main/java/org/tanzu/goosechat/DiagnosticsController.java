package org.tanzu.goosechat;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/diagnostics")
@CrossOrigin(origins = "*")
public class DiagnosticsController {

    @GetMapping("/env")
    public Map<String, String> getEnvironment() {
        Map<String, String> filtered = new TreeMap<>();
        Map<String, String> env = System.getenv();
        
        // Only show Goose and provider-related variables
        env.forEach((key, value) -> {
            if (key.contains("GOOSE") || 
                key.contains("ANTHROPIC") || 
                key.contains("OPENAI") ||
                key.contains("GOOGLE") ||
                key.contains("DATABRICKS") ||
                key.contains("OLLAMA") ||
                key.equals("PATH") || 
                key.equals("HOME")) {
                // Mask API keys for security
                if ((key.contains("API_KEY") || key.contains("TOKEN")) 
                    && value != null && value.length() > 10) {
                    filtered.put(key, value.substring(0, 10) + "..." + value.substring(value.length() - 4));
                } else {
                    filtered.put(key, value);
                }
            }
        });
        
        return filtered;
    }
}

