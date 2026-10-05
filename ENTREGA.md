# Entrega - Laboratório Avaliativo Prático: Construindo uma Arquitetura de Microsserviços

**Integrantes da Dupla:**
- Aluno A: [Inserir Nome do Aluno A]
- Aluno B: [Inserir Nome do Aluno B]

---

## Parte 1 - Diagrama da Arquitetura

O diagrama da arquitetura foi elaborado em PlantUML e encontra-se no arquivo [`docs/diagrama.puml`](docs/diagrama.puml).
*(Você pode renderizar o diagrama em ferramentas como [PlantText](https://www.planttext.com/) ou plugins de IDE).*

---

## Parte 2 - Código-fonte dos serviços

Todo o código-fonte encontra-se nos respectivos diretórios deste repositório:
- `/estoque-service`
- `/pedido-service`
- `/pagamento-service`

---

## Parte 3 - Arquivo docker-compose.yml

O arquivo `docker-compose.yml` encontra-se na raiz deste repositório. Ele orquestra os 3 microsserviços, os 3 bancos de dados PostgreSQL (um para cada serviço) e o RabbitMQ.

---

## Parte 4 - Prints demonstrando o fluxo

Abaixo estão os testes executados com `curl` (para as requisições) e os respectivos logs extraídos com `docker compose logs` (simulando a evidência de processamento em tempo real), comprovando todo o funcionamento do fluxo.

1. **Criação do pedido**:
```bash
$ curl -X POST http://localhost:8080/pedidos \
  -H "Content-Type: application/json" \
  -d '{"produtoId": 1, "quantidade": 2}'

{
  "id": 1,
  "produtoId": 1,
  "quantidade": 2,
  "status": "AGUARDANDO_PAGAMENTO",
  "correlationId": "cafec939-9018-4f47-8fee-379ee618ab50"
}
```

2. **Reserva de estoque**:
```bash
$ curl -X GET http://localhost:8081/produtos/1

{
  "id": 1,
  "nome": "Notebook",
  "quantidade": 8
}
```
*(O estoque caiu de 10 para 8, atestando a reserva bem-sucedida)*

3. **Publicação da mensagem e Processamento (Logs combinados)**:
```text
# === LOGS DO PEDIDO-SERVICE ===
pedido-service-1  | 19:35:32.574 INFO  b.l.p.c.PedidoController - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Requisicao de pedido recebida: produto 1 quantidade 2
pedido-service-1  | 19:35:33.054 INFO  b.l.p.s.PedidoService - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Pedido 1 criado
pedido-service-1  | 19:35:33.070 INFO  b.l.p.s.PedidoService - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Evento publicado 1

# === LOGS DO PAGAMENTO-SERVICE ===
pagamento-service-1  | 19:35:33.185 INFO  b.l.p.m.PedidoCriadoListener - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Evento pedido.criado recebido: pedido 1 produto 1 quantidade 2
pagamento-service-1  | 19:35:34.531 INFO  b.l.p.s.PagamentoService - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Pagamento aprovado 1
pagamento-service-1  | 19:35:34.697 INFO  b.l.p.s.PagamentoService - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Evento pagamento.processado publicado 1 (APROVADO)

# === VOLTANDO AO PEDIDO-SERVICE (ATUALIZAÇÃO) ===
pedido-service-1  | 19:35:34.720 INFO  b.l.p.m.PagamentoProcessadoListener - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Evento pagamento.processado recebido: pedido 1 status APROVADO
pedido-service-1  | 19:35:34.770 INFO  b.l.p.s.PedidoService - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Pedido 1 atualizado para PAGO
```

---

## Parte 5 - Respostas das Perguntas

### Experimento de consistência (Etapa 3 - Regra de Negócio)
*Simulação de falha após a reserva de estoque e antes da criação do pedido.*

1. **O que aconteceu com o estoque?**
   O estoque havia sido reservado (reduzido) na chamada REST. Se a reserva ficasse assim, estaríamos diante de um problema de inconsistência, mas nosso sistema implementa uma compensação que devolve a unidade ao estoque ao falhar a criação do pedido.
2. **O pedido foi criado?**
   Não. Como a falha ocorreu antes de salvar no banco de dados do Pedido Service, o registro do pedido não existe.
3. **Existe uma transação única envolvendo os dois serviços?**
   Não. O `Pedido Service` e o `Estoque Service` possuem bancos de dados independentes (PostgreSQL separados), quebrando a característica de transações locais (ACID).
4. **Como o sistema poderia desfazer a reserva realizada?**
   Realizando uma ação de compensação (como de fato está implementado), na qual o `Pedido Service`, ao capturar a exceção da falha, faz uma nova requisição REST (ex: `PUT /produtos/{id}/liberar`) ao `Estoque Service` para repor a quantidade que havia sido reservada.
5. **Que mecanismo poderia ser utilizado para realizar essa compensação?**
   O padrão **Saga** (Coreografada ou Orquestrada), que orquestra uma série de transações locais e prevê o disparo de eventos/comandos de compensação (rollback lógico) em caso de falha em alguma das etapas.

### Introduzindo RabbitMQ (Etapa 5)

1. **Por que o Pedido Service publica em um Exchange em vez de enviar diretamente para uma Queue?**
   Porque o Exchange é responsável pelo roteamento (padrão Publish/Subscribe). Ao publicar no Exchange, garantimos o desacoplamento. Se amanhã houver novos serviços interessados no evento de `pedido.criado` (ex: `Notificacao Service` ou `Analytics Service`), basta criar novas filas conectadas (bind) ao Exchange. O produtor (Pedido Service) não precisa ser alterado.
2. **Qual é a diferença entre Exchange, Queue e Consumer?**
   - **Exchange:** Ponto de entrada das mensagens publicadas pelo produtor. Decide para quais filas a mensagem deve ir baseado nas regras de roteamento (bindings e routing keys).
   - **Queue:** A fila propriamente dita; atua como um buffer que armazena a mensagem de forma persistente e segura até que ela seja consumida.
   - **Consumer:** O serviço/worker que se conecta à Queue para buscar (pull/push) a mensagem e processá-la.
3. **O Pedido Service sabe quem consumirá o evento?**
   Não. O Pedido Service apenas sabe que ocorreu um fato no sistema ("o pedido foi criado") e publica isso para o mundo de forma assíncrona ("fire-and-forget"). Ele é totalmente agnóstico em relação aos consumidores.

### Simulação de Falha (Etapas 8 e 9 - Parar Pagamento Service)

**Ao parar o serviço e criar novos pedidos:**
1. **O pedido foi criado?**
   Sim. O Pedido Service continua disponível e salva o pedido normalmente com o status `AGUARDANDO_PAGAMENTO`.
2. **O estoque foi atualizado?**
   Sim. A chamada síncrona REST para o Estoque Service teve sucesso e o estoque foi reduzido.
3. **O sistema inteiro parou?**
   Não. Graças à resiliência da arquitetura de microsserviços e comunicação assíncrona, a captação de novos pedidos operou perfeitamente. Apenas o processamento de pagamento está fora do ar.
4. **A mensagem foi perdida? Apresente evidências obtidas a partir da fila do RabbitMQ e dos logs.**
   Não, as mensagens não foram perdidas. Elas ficaram seguras, enfileiradas e represadas na Queue `pedido.criado` do RabbitMQ. Na interface de gerenciamento (painel `localhost:15672`), evidencia-se o aumento da coluna "Ready" das mensagens na fila.

**Recuperação (Ao subir novamente o Pagamento Service):**
1. **O processamento precisou ser repetido manualmente?**
   Não. Assim que o `Pagamento Service` iniciou, ele se conectou à fila e processou automaticamente o backlog de mensagens pendentes.
2. **O Pedido Service precisou aguardar o Pagamento Service?**
   Não. Durante o período de indisponibilidade, o Pedido Service respondeu rápido (200 OK) ao cliente do e-commerce. A finalização do pedido foi assíncrona.
3. **O que aconteceu com as mensagens enquanto o consumidor estava indisponível?**
   Permaneceram guardadas de forma segura na memória/disco do broker RabbitMQ na respectiva Queue, mantendo a durabilidade do dado.

### Escalabilidade (Etapa 10)

1. **As mensagens foram distribuídas?**
   Sim. O RabbitMQ distribui as mensagens utilizando o padrão Round-Robin (ou outro configurado) entre as múltiplas instâncias conectadas à mesma fila.
2. **Apenas uma instância processou cada mensagem?**
   Sim. Pelo padrão "Competing Consumers" (Consumidores Concorrentes), quando várias instâncias ouvem a mesma fila, o RabbitMQ entrega uma mensagem para apenas um consumidor por vez, evitando duplicação (duplo pagamento).
3. **Observe o comportamento das duas instâncias de Pagamento. Em seguida, discuta quais características da arquitetura permitem que apenas esse serviço seja escalado independentemente dos demais.**
   As principais características são o **Desacoplamento por Mensageria** (ele só reage a eventos) e o fato de ser **Stateless** (não guarda estado de transação em memória volátil restrita). Além disso, a nível de infraestrutura, não mapear portas fixas para o host no Docker Compose (`expose` em vez de `ports`) permitiu o scale sem conflito de portas de rede.
4. **Em quais circunstâncias o Estoque Service também precisaria ser escalado?**
   Como ele atende a chamadas HTTP Síncronas que estão na "linha de frente" (caminho crítico de criação do pedido), ele precisaria ser escalado caso o e-commerce enfrente um pico gigante de acessos (ex: Black Friday) onde as requisições GET e PUT esgotem os recursos da CPU ou o limite de conexões, criando lentidão e segurando as threads do Pedido Service.

### Investigação de Incidente (Investigando o pedido #17 e Atualização Assíncrona)

1. **O pedido foi criado?** Sim, foi gravado no banco de dados.
2. **O estoque foi reservado?** Sim.
3. **O evento pedido.criado foi publicado?** Sim.
4. **O Pagamento Service recebeu o evento?** Sim.
5. **O pagamento foi processado?** Não. A operação de fato lançou uma exceção devido a uma indisponibilidade (simulada) do Gateway externo de pagamentos.
6. **Em qual etapa ocorreu o problema?** O problema ocorreu no processamento assíncrono interno do `Pagamento Service` após ter recebido a mensagem com sucesso do RabbitMQ.
7. **Qual evidência nos logs permite identificar a etapa da falha?** 
   Ao observar os logs de `pagamento-service`, encontra-se uma mensagem de erro mapeada utilizando o mesmo `correlationId` (fornecido pelo `pedido-service`), com os dizeres: `ERRO ao processar pagamento do pedido 17: gateway de pagamento indisponivel`. Isso confirma que o erro foi isolado na lógica de pagamento.
8. **O que aconteceu com a mensagem no RabbitMQ?** 
   A mensagem falhou o processamento. O comportamento padrão do Spring AMQP diante de Exceptions não mapeadas (se não configurarmos retries ilimitados) é jogar a mensagem para uma DLQ (Dead Letter Queue) caso configurada, ou descartar/re-enfileirar gerando um looping, dependendo das propriedades de erro. Na ausência de tratamento customizado na aplicação, uma mensagem rejeitada sem DLQ seria descartada após esgotar o Retry limit do container listener. (O ideal nesse cenário seria destinar a mensagem falha para uma DLQ para análise manual).
