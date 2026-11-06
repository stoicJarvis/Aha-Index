package io.aha.index;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  @GetMapping("/v1/health")
  public HealthView health() {
    return new HealthView("ok", "naive");
  }

  public record HealthView(String status, String mode) {}
}
