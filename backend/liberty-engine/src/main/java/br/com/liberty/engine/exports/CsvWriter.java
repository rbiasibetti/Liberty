package br.com.liberty.engine.exports;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * CSV (RFC 4180, UTF-8 com BOM para o Excel) com defesa contra injeção de fórmula: texto que começa
 * com {@code = + - @}, tabulação ou retorno de carro ganha um apóstrofo na frente. Números e
 * booleanos não são texto e ficam como estão. Conta os bytes escritos para o limite de tamanho.
 */
final class CsvWriter implements AutoCloseable {

  /** O arquivo passou do limite de tamanho. */
  static final class TooLarge extends IOException {
    private static final long serialVersionUID = 1L;
  }

  private final Writer writer;
  private final CountingStream counting;
  private final long maxBytes;

  CsvWriter(OutputStream out, long maxBytes) throws IOException {
    this.counting = new CountingStream(out);
    this.writer = new OutputStreamWriter(counting, StandardCharsets.UTF_8);
    this.maxBytes = maxBytes;
    writer.write('﻿');
  }

  void header(List<String> columns) throws IOException {
    row(columns.stream().map(c -> (Object) c).toList(), true);
  }

  void row(Map<String, Object> item, List<String> columns) throws IOException {
    row(columns.stream().map(item::get).toList(), false);
  }

  private void row(List<Object> cells, boolean names) throws IOException {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        writer.write(',');
      }
      writer.write(cell(cells.get(i), names));
    }
    writer.write("\r\n");
    writer.flush();
    if (counting.count > maxBytes) {
      throw new TooLarge();
    }
  }

  static String cell(Object value, boolean forceText) {
    if (value == null) {
      return "";
    }
    boolean plain = !forceText && (value instanceof Number || value instanceof Boolean);
    String text = value instanceof BigDecimal d ? d.toPlainString() : value.toString();
    if (!plain && !text.isEmpty()) {
      char first = text.charAt(0);
      if (first == '='
          || first == '+'
          || first == '-'
          || first == '@'
          || first == '\t'
          || first == '\r') {
        text = "'" + text;
      }
    }
    if (text.indexOf(',') >= 0
        || text.indexOf('"') >= 0
        || text.indexOf('\n') >= 0
        || text.indexOf('\r') >= 0) {
      return '"' + text.replace("\"", "\"\"") + '"';
    }
    return text;
  }

  @Override
  public void close() throws IOException {
    writer.flush();
  }

  private static final class CountingStream extends OutputStream {
    private final OutputStream out;
    long count;

    CountingStream(OutputStream out) {
      this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      count++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      count += len;
    }
  }
}
