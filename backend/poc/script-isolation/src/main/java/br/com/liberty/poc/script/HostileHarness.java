package br.com.liberty.poc.script;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Catálogo de scripts hostis e executor de linha de comando (usado nos testes e no benchmark). */
public final class HostileHarness {

  /** Cada entrada: nome do ataque → corpo do script que define {@code f(input)}. */
  public static final Map<String, String> ATTACKS = new LinkedHashMap<>();

  static {
    ATTACKS.put("laco-infinito", "function f(i){ while(true){} }");
    ATTACKS.put("laco-com-chamadas", "function f(i){ var n=0; for(;;){ n+=Math.sqrt(n+1); } }");
    ATTACKS.put(
        "regex-catastrofica", "function f(i){ return /^(a+)+$/.test('a'.repeat(60)+'b'); }");
    ATTACKS.put("recursao-infinita", "function f(i){ function g(){ return g()+1; } return g(); }");
    ATTACKS.put(
        "java-type", "function f(i){ return Java.type('java.lang.System').getenv('HOME'); }");
    ATTACKS.put(
        "packages", "function f(i){ return Packages.java.lang.System.getProperty('user.home'); }");
    ATTACKS.put(
        "java-global", "function f(i){ return java.lang.Runtime.getRuntime().exec('id'); }");
    ATTACKS.put("reflexao-no-api", "function f(i){ return api.getClass().getName(); }");
    ATTACKS.put("metodo-nao-exportado", "function f(i){ return api.secret(); }");
    ATTACKS.put("load-arquivo", "function f(i){ return load('/etc/passwd'); }");
    ATTACKS.put("require", "function f(i){ return require('fs').readFileSync('/etc/passwd'); }");
    ATTACKS.put("import-dinamico", "function f(i){ return import('node:fs'); }");
    ATTACKS.put(
        "atomics-wait",
        "function f(i){ Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0); }");
    ATTACKS.put("timer", "function f(i){ setTimeout(function(){},1); return 1; }");
    ATTACKS.put(
        "flood-de-log",
        "function f(i){ for(var k=0;k<2000000;k++){ console.log('x'.repeat(100)); } return 1; }");
    ATTACKS.put(
        "vazamento-de-estado",
        "function f(i){ globalThis.segredo = (globalThis.segredo||0)+1; return globalThis.segredo; }");
    ATTACKS.put(
        "bomba-de-memoria",
        "function f(i){ var a=[]; for(;;){ a.push(new Array(1e6).fill(1)); } }");
    ATTACKS.put("bomba-de-string", "function f(i){ var s='x'; for(;;){ s=s+s; } }");
    ATTACKS.put(
        "bomba-de-objetos",
        "function f(i){ var a=[]; for(var k=0;;k++){ a.push({k:k,v:'v'+k}); } }");
  }

  private HostileHarness() {}

  /** Uso: HostileHarness [ataque ...]; sem argumentos roda todos. Imprime uma linha por ataque. */
  public static void main(String[] args) {
    try (ScriptRunner runner = new ScriptRunner();
        PrintWriter out = new PrintWriter(System.out, true)) {
      for (var attack : ATTACKS.entrySet()) {
        if (args.length > 0 && !java.util.List.of(args).contains(attack.getKey())) {
          continue;
        }
        var r = runner.run(attack.getValue(), "f", "{}", Duration.ofSeconds(2), 50_000_000L);
        out.println(
            attack.getKey()
                + " | "
                + r.status()
                + " | "
                + r.millis()
                + "ms | "
                + String.valueOf(r.message()).replace('\n', ' '));
      }
    }
  }
}
