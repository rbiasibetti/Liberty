package br.com.liberty.snapshot;

import java.io.IOException;
import java.io.InputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Carrega os snapshots de exemplo e aplica alterações por JSON Pointer para montar documentos
 * defeituosos.
 */
final class Fixtures {

  static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private Fixtures() {}

  static ObjectNode load(String name) {
    try (InputStream stream = Fixtures.class.getResourceAsStream("/snapshots/" + name)) {
      return (ObjectNode) MAPPER.readTree(stream);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static ObjectNode insert() {
    return load("insert-pedido.json");
  }

  static ObjectNode select() {
    return load("select-pedidos.json");
  }

  static ObjectNode delete() {
    return load("delete-cliente.json");
  }

  static ObjectNode upsert() {
    return load("upsert-cliente.json");
  }

  /** Troca (ou cria) o valor em {@code pointer}; o pai precisa existir. */
  static ObjectNode set(ObjectNode doc, String pointer, Object value) {
    int cut = pointer.lastIndexOf('/');
    JsonNode parent = pointer.substring(0, cut).isEmpty() ? doc : doc.at(pointer.substring(0, cut));
    String key = pointer.substring(cut + 1);
    JsonNode node = MAPPER.valueToTree(value);
    if (parent instanceof ObjectNode object) {
      object.set(key, node);
    } else {
      var array = (tools.jackson.databind.node.ArrayNode) parent;
      int index = Integer.parseInt(key);
      if (index == array.size()) {
        array.add(node);
      } else {
        array.set(index, node);
      }
    }
    return doc;
  }

  static ObjectNode remove(ObjectNode doc, String pointer) {
    int cut = pointer.lastIndexOf('/');
    JsonNode parent = pointer.substring(0, cut).isEmpty() ? doc : doc.at(pointer.substring(0, cut));
    ((ObjectNode) parent).remove(pointer.substring(cut + 1));
    return doc;
  }

  static byte[] bytes(JsonNode doc) {
    return MAPPER.writeValueAsBytes(doc);
  }
}
