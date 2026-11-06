# Política de segurança

Vulnerabilidades devem ser reportadas em privado ao responsável pelo projeto, nunca em issues ou PRs públicos. Inclua passos para reproduzir, impacto e versão afetada; a confirmação de recebimento ocorre em até 2 dias úteis.

Princípios obrigatórios do produto: o tenant vem só do JWT; segredos só em cofre; nenhum dado pessoal em log; SQL com identificadores validados e valores parametrizados; scripts de integrador sem acesso a classes Java, arquivos ou rede. Detalhes nos [ADRs](docs/adr/README.md).
