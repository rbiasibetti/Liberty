// Corpo de uma escrita master-detail com `items` itens (padrão 100, o caso do SLO).
export function pedido(vu, iteration, items = 100) {
  return JSON.stringify({
    pedido: {
      numero: `K6-${vu}-${iteration}-${Date.now()}`,
      cliente: { documento: '11144477735' },
      itens: Array.from({ length: items }, (_, i) => ({
        sku: `SKU-${i}`,
        qtd: (i % 5) + 1,
        preco: 10.5 + i,
      })),
    },
  });
}
