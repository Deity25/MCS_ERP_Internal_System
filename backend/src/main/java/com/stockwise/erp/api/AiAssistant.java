package com.stockwise.erp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockwise.erp.service.ErpService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AiAssistant {
    private static final String INSTRUCTIONS = "You are Stockwise, an operations assistant for a wholesale distribution ERP. Answer from the supplied snapshot only. State when information is missing. Do not invent stock, sales, tax, or financial facts. Give practical, concise recommendations. A question is informational only; you cannot execute transactions.";
    private final ErpService erp;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public AiAssistant(ErpService erp, ObjectMapper mapper,
                       @Value("${app.ai.api-key:}") String apiKey,
                       @Value("${app.ai.model:gpt-4.1-mini}") String model) {
        this.erp = erp;
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.client = RestClient.builder().baseUrl("https://api.openai.com/v1").build();
    }

    public String provider() { return apiKey.isBlank() ? "rules" : "openai"; }

    public String answer(long organizationId, String question) {
        Map<String, Object> context = erp.assistantContext(organizationId);
        if (apiKey.isBlank()) return localAnswer(context);
        try {
            Map<String, Object> body = Map.of("model", model, "instructions", INSTRUCTIONS,
                    "input", "ERP snapshot (JSON): " + mapper.writeValueAsString(context) + "\n\nOperator question: " + question);
            JsonNode response = client.post().uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(JsonNode.class);
            if (response == null) throw new IllegalStateException("Empty assistant response");
            String output = response.path("output_text").asText("").trim();
            if (output.isBlank()) {
                List<String> fragments = new ArrayList<>();
                for (JsonNode item : response.path("output")) for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asText())) fragments.add(part.path("text").asText());
                }
                output = String.join("\n", fragments).trim();
            }
            if (output.isBlank()) throw new IllegalStateException("Assistant returned no text");
            return output;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The AI assistant is temporarily unavailable. Reorder suggestions remain available.");
        }
    }

    private String localAnswer(Map<String, Object> context) {
        List<?> suggestions = (List<?>) context.get("reorderSuggestions");
        if (suggestions.isEmpty()) return "OpenAI is not configured, and the current stock snapshot has no items within twice their reorder point.";
        List<String> names = new ArrayList<>();
        for (Object row : suggestions.stream().limit(4).toList()) {
            if (row instanceof Map<?, ?> product) names.add(product.get("name") + " (suggested order: " + product.get("suggestedOrder") + ")");
        }
        return "OpenAI is not configured, so I used the local replenishment rules. Review these items first: " + String.join(", ", names) + ". Configure OPENAI_API_KEY to ask free-form questions about the current ERP snapshot.";
    }

    public record Question(@NotBlank String question) { }
}
