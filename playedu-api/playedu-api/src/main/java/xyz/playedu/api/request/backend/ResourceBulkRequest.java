package xyz.playedu.api.request.backend;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ResourceBulkRequest(
        @NotBlank String operation,
        @NotEmpty @Size(max = 200) List<Integer> ids,
        @JsonProperty("category_ids") @Size(max = 50) List<Integer> categoryIds) {}
