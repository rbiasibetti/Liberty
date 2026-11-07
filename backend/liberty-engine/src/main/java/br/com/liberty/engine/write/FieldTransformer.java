package br.com.liberty.engine.write;

import br.com.liberty.snapshot.model.Transform;
import java.util.List;

/**
 * Aplica a cadeia de funções de transformação de um campo ao valor (texto ou {@code null}) antes de
 * ele ser convertido para o tipo da coluna. As funções da lista fechada são da E13.
 */
@FunctionalInterface
public interface FieldTransformer {

  /**
   * O valor transformado; {@code null} representa NULL. Falhas viram {@link TransformException}.
   */
  String apply(String value, List<Transform> transforms);

  /** O valor não pôde ser transformado (ex.: data fora do formato). */
  final class TransformException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TransformException(String message) {
      super(message);
    }
  }

  /** Enquanto não há funções, uma cadeia não vazia é recusada como erro de configuração. */
  FieldTransformer NONE =
      (value, transforms) -> {
        if (!transforms.isEmpty()) {
          throw new IllegalStateException("Funções de transformação ainda não disponíveis");
        }
        return value;
      };
}
