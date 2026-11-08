package br.com.liberty.web;

import br.com.liberty.engine.exports.ExportService;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Download por URL assinada. É o único endpoint de aplicação sem login: a autorização é o token
 * (HMAC com tenant, pedido e validade dentro), conferido de novo contra o tenant dono do pedido.
 */
@RestController
@RequestMapping("/api/v1/exports")
class ExportDownloadController {

  private final ExportService exports;

  ExportDownloadController(ExportService exports) {
    this.exports = exports;
  }

  @GetMapping("/download")
  @PreAuthorize("permitAll()")
  ResponseEntity<StreamingResponseBody> download(@RequestParam String token) {
    ExportService.Download file = exports.download(token);
    StreamingResponseBody body =
        out -> {
          try (var in = file.stream()) {
            in.transferTo(out);
          } catch (IOException e) {
            throw e;
          }
        };
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(file.contentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .body(body);
  }
}
