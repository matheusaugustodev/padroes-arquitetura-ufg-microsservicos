# microservices-lab

## Autores

| Estudante | Matrícula |
|---|---|
| Matheus Augusto Ferreira Medeiros | 202305532 |
| Marcello Ronald José da Silva | 202302618 |

Plataforma de e-commerce com 3 microsserviços (Java 17 + Spring Boot 3.3), PostgreSQL por serviço e RabbitMQ.

| Serviço | Porta no host | Banco | Papel |
|---|---|---|---|
| pedido-service | 8080 | pedido-db | API de pedidos; chama o Estoque via REST; publica `pedido.criado`; consome `pagamento.processado` |
| estoque-service | 8081 | estoque-db | Consulta e reserva de estoque |
| pagamento-service | — (só rede interna) | pagamento-db | Consome `pedido.criado`, aprova ~80% / rejeita ~20%, publica `pagamento.processado` |
| rabbitmq | 5672 / 15672 | — | Painel: http://localhost:15672 (guest / guest) |

Requisito: Docker Desktop (ou Docker Engine + Compose). Não é preciso ter Java/Maven instalados — o build acontece dentro do Docker.

## Subir

```bash
docker compose up --build
```

Na primeira vez demora alguns minutos (download das dependências Maven). Para recomeçar do zero (zera os bancos):

```bash
docker compose down -v
```

## Topologia do RabbitMQ

| Exchange | Routing key | Fila | Consumidor |
|---|---|---|---|
| `pedidos.exchange` (topic) | `pedido.criado` | `pedido.criado` | pagamento-service |
| `pagamentos.exchange` (topic) | `pagamento.processado` | `pagamento.processado` | pedido-service |
| `pedidos.dlx` (direct) | `pedido.criado.dlq` | `pedido.criado.dlq` | — (mensagens que falharam 3x) |
| `pagamentos.dlx` (direct) | `pagamento.processado.dlq` | `pagamento.processado.dlq` | — |

---

# Roteiro de testes

Os comandos usam cURL (Linux/macOS/Git Bash). No PowerShell use `curl.exe` no lugar de `curl`.

## Etapa 1 — Estoque

```bash
curl -s localhost:8081/produtos
curl -s localhost:8081/produtos/1
curl -i -X PUT localhost:8081/produtos/1/reservar -H "Content-Type: application/json" -d '{"quantidade":2}'     # 200
curl -i -X PUT localhost:8081/produtos/1/reservar -H "Content-Type: application/json" -d '{"quantidade":999}'   # 409 Estoque insuficiente
curl -i -X PUT localhost:8081/produtos/99/reservar -H "Content-Type: application/json" -d '{"quantidade":1}'    # 404 Produto inexistente
```

## Etapas 2, 3 e 7 — Criar pedido (fluxo completo)

```bash
curl -s localhost:8081/produtos/1                        # anote a quantidade ANTES
curl -i -X POST localhost:8080/pedidos -H "Content-Type: application/json" -d '{"produtoId":1,"quantidade":2}'
curl -s localhost:8081/produtos/1                        # quantidade DEPOIS (2 a menos)
curl -s localhost:8080/pedidos                           # status AGUARDANDO_PAGAMENTO e, ~1s depois, PAGO ou REJEITADO
docker compose logs pedido-service estoque-service pagamento-service | grep <correlationId>
docker compose exec pagamento-db psql -U pagamento -c "select * from pagamento;"
docker compose exec pedido-db   psql -U pedido    -c "select * from pedido;"
```

resposta 201 do POST (com o header `X-Correlation-Id`), estoque antes/depois, logs dos 3 serviços filtrados pelo correlationId, `select` da tabela pagamento.

Erros que não podem criar pedido nem publicar evento:

```bash
curl -i -X POST localhost:8080/pedidos -H "Content-Type: application/json" -d '{"produtoId":1,"quantidade":999}'  # 409
curl -i -X POST localhost:8080/pedidos -H "Content-Type: application/json" -d '{"produtoId":99,"quantidade":1}'   # 404
```

## Etapa 3 — Experimento de consistência

```bash
curl -s localhost:8081/produtos/2                                                         # quantidade antes
# Falha APÓS a reserva e ANTES de criar o pedido, SEM compensação:
curl -i -X POST "localhost:8080/pedidos?simularFalha=true&compensar=false" -H "Content-Type: application/json" -d '{"produtoId":2,"quantidade":5}'
curl -s localhost:8081/produtos/2                                                         # 5 a menos, mas nenhum pedido criado
# Mesma falha, COM compensação (padrão):
curl -i -X POST "localhost:8080/pedidos?simularFalha=true" -H "Content-Type: application/json" -d '{"produtoId":2,"quantidade":5}'
curl -s localhost:8081/produtos/2                                                         # quantidade volta ao valor anterior
docker compose logs estoque-service | grep -E "reservado|liberada"
```

## Etapa 4 — Pedido consegue acessar o Estoque?

```bash
docker compose ps
docker compose logs pedido-service | grep "criado"
docker compose logs estoque-service | grep "reservado"
docker compose exec pedido-service getent hosts estoque-service   # resolução de nome pela rede do Compose
```

## Etapa 5 — RabbitMQ

Abra http://localhost:15672 → aba **Exchanges** (`pedidos.exchange`, veja os *Bindings*) e aba **Queues** (`pedido.criado`: colunas *Ready*, *Unacked*, *Consumers*).

## Etapa 8 — Falha do Pagamento

```bash
docker compose stop pagamento-service
for i in 1 2 3; do curl -s -X POST localhost:8080/pedidos -H "Content-Type: application/json" -d '{"produtoId":3,"quantidade":1}'; echo; done
curl -s localhost:8080/pedidos            # novos pedidos AGUARDANDO_PAGAMENTO
```

Print: painel do RabbitMQ com a fila `pedido.criado` mostrando *Ready = 3* e *Consumers = 0*. Linha de comando alternativa:

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

## Etapa 9 — Recuperação

```bash
docker compose start pagamento-service
docker compose logs -f pagamento-service    # consome as mensagens pendentes
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready consumers   # Ready volta a 0
docker compose exec pagamento-db psql -U pagamento -c "select * from pagamento order by id;"
```

## Etapa 10 — Escalabilidade

```bash
docker compose up -d --scale pagamento-service=2
for i in $(seq 1 10); do curl -s -X POST localhost:8080/pedidos -H "Content-Type: application/json" -d '{"produtoId":2,"quantidade":1}' > /dev/null; done
docker compose logs pagamento-service | grep "Pagamento aprovado\|Pagamento rejeitado"
```

Os logs aparecem prefixados por `pagamento-service-1` e `pagamento-service-2`. No painel, a fila `pedido.criado` mostra *Consumers = 2*.

## Etapa 11 — Investigação de incidente (Pedido #17)

Para reproduzir uma falha real no pedido 17, descomente `SIMULACAO_FALHAR_PEDIDO_ID: "17"` no `docker-compose.yml` e recrie o serviço:

```bash
docker compose up -d pagamento-service
# crie pedidos até chegar ao id 17 (se já passou do 17, use o próximo id que será criado,
# ou rode "docker compose down -v" antes para zerar os bancos)
curl -s localhost:8080/pedidos/17                       # status preso em AGUARDANDO_PAGAMENTO; pegue o correlationId
docker compose logs pedido-service    | grep <correlationId>
docker compose logs estoque-service   | grep <correlationId>
docker compose logs pagamento-service | grep <correlationId>
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready
```

A mensagem do pedido 17 fica na fila `pedido.criado.dlq` (painel → Queues → `pedido.criado.dlq` → *Get messages* mostra o JSON).

## Etapa 12 — Status assíncrono

```bash
for i in $(seq 1 10); do curl -s -X POST localhost:8080/pedidos -H "Content-Type: application/json" -d '{"produtoId":2,"quantidade":1}' > /dev/null; done
sleep 15
curl -s localhost:8080/pedidos                     # deve haver PAGO e REJEITADO
docker compose logs pedido-service | grep "atualizado para"
```

Pedidos REJEITADOS devolvem a quantidade ao estoque (log `Estoque do pedido X devolvido`).

---

# Entrega - Laboratório Avaliativo Prático

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

## Parte 4 -  demonstrando o fluxo

Abaixo estão os testes executados com `curl` e os respectivos logs extraídos com `docker compose logs`, comprovando todo o funcionamento do fluxo.

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

2. **Reserva de estoque** (quantidade caiu de 10 para 8):
```bash
$ curl -X GET http://localhost:8081/produtos/1

{
  "id": 1,
  "nome": "Notebook",
  "quantidade": 8
}
```

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

# === VOLTANDO AO PEDIDO-SERVICE (ATUALIZAÇÃO DE STATUS) ===
pedido-service-1  | 19:35:34.720 INFO  b.l.p.m.PagamentoProcessadoListener - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Evento pagamento.processado recebido: pedido 1 status APROVADO
pedido-service-1  | 19:35:34.770 INFO  b.l.p.s.PedidoService - correlationId=cafec939-9018-4f47-8fee-379ee618ab50 Pedido 1 atualizado para PAGO
```

---

## Parte 5 - Respostas das Perguntas

### Experimento de consistência (Etapa 3)
*Simulação de falha após a reserva de estoque e antes da criação do pedido.*

1. **O que aconteceu com o estoque?**
   O estoque havia sido reservado (reduzido) na chamada REST. Se a reserva ficasse assim, estaríamos diante de um problema de inconsistência, mas nosso sistema implementa uma compensação que devolve a unidade ao estoque ao falhar a criação do pedido.
2. **O pedido foi criado?**
   Não. Como a falha ocorreu antes de salvar no banco de dados do Pedido Service, o registro do pedido não existe.
3. **Existe uma transação única envolvendo os dois serviços?**
   Não. O `Pedido Service` e o `Estoque Service` possuem bancos de dados independentes (PostgreSQL separados), quebrando a característica de transações locais (ACID).
4. **Como o sistema poderia desfazer a reserva realizada?**
   Realizando uma ação de compensação (como de fato está implementado), na qual o `Pedido Service`, ao capturar a exceção da falha, faz uma nova requisição REST (`PUT /produtos/{id}/liberar`) ao `Estoque Service` para repor a quantidade reservada.
5. **Que mecanismo poderia ser utilizado para realizar essa compensação?**
   O padrão **Saga** (Coreografada ou Orquestrada), que orquestra uma série de transações locais e prevê o disparo de eventos/comandos de compensação (rollback lógico) em caso de falha em alguma das etapas.

### Introduzindo RabbitMQ (Etapa 5)

1. **Por que o Pedido Service publica em um Exchange em vez de enviar diretamente para uma Queue?**
   Porque o Exchange é responsável pelo roteamento (padrão Publish/Subscribe). Ao publicar no Exchange, garantimos o desacoplamento. Se amanhã houver novos serviços interessados no evento de `pedido.criado` (ex: `Notificacao Service` ou `Analytics Service`), basta criar novas filas conectadas (bind) ao Exchange sem alterar o produtor.
2. **Qual é a diferença entre Exchange, Queue e Consumer?**
   - **Exchange:** Ponto de entrada das mensagens publicadas pelo produtor. Decide para quais filas a mensagem deve ir baseado nas regras de roteamento (bindings e routing keys).
   - **Queue:** A fila propriamente dita; atua como um buffer que armazena a mensagem de forma persistente e segura até que ela seja consumida.
   - **Consumer:** O serviço/worker que se conecta à Queue para buscar (pull/push) a mensagem e processá-la.
3. **O Pedido Service sabe quem consumirá o evento?**
   Não. O Pedido Service apenas sabe que ocorreu um fato no sistema ("o pedido foi criado") e publica isso de forma assíncrona ("fire-and-forget"). Ele é totalmente agnóstico em relação aos consumidores.

### Simulação de Falha (Etapas 8 e 9)

**Ao parar o serviço e criar novos pedidos:**
1. **O pedido foi criado?** Sim. O Pedido Service continua disponível e salva o pedido com status `AGUARDANDO_PAGAMENTO`.
2. **O estoque foi atualizado?** Sim. A chamada síncrona REST para o Estoque Service teve sucesso.
3. **O sistema inteiro parou?** Não. A captação de novos pedidos operou perfeitamente. Apenas o processamento de pagamento estava fora do ar.
4. **A mensagem foi perdida?** Não. As mensagens ficaram seguras e represadas na Queue `pedido.criado` do RabbitMQ. No painel (`localhost:15672`), evidencia-se o aumento da coluna "Ready" com *Consumers = 0*.

**Recuperação (Ao subir novamente o Pagamento Service):**
1. **O processamento precisou ser repetido manualmente?** Não. Assim que o serviço iniciou, ele se conectou à fila e processou automaticamente o backlog de mensagens pendentes.
2. **O Pedido Service precisou aguardar o Pagamento Service?** Não. Durante a indisponibilidade, o Pedido Service respondeu normalmente (200 OK) ao cliente. A finalização foi assíncrona.
3. **O que aconteceu com as mensagens enquanto o consumidor estava indisponível?** Permaneceram guardadas de forma segura no broker RabbitMQ na respectiva Queue, mantendo a durabilidade do dado.

### Escalabilidade (Etapa 10)

1. **As mensagens foram distribuídas?** Sim. O RabbitMQ distribui as mensagens em Round-Robin entre as múltiplas instâncias conectadas à mesma fila.
2. **Apenas uma instância processou cada mensagem?** Sim. Pelo padrão "Competing Consumers", o RabbitMQ entrega uma mensagem para apenas um consumidor por vez, evitando duplicação (duplo pagamento).
3. **Quais características permitem escalar apenas esse serviço?** O **desacoplamento por mensageria** (ele só reage a eventos) e o fato de ser **stateless**. A nível de infraestrutura, o uso de `expose` em vez de `ports` no Docker Compose permitiu o scale sem conflito de portas.
4. **Em quais circunstâncias o Estoque Service também precisaria ser escalado?** Por atender chamadas HTTP síncronas no caminho crítico de criação do pedido, precisaria ser escalado em picos de acesso (ex: Black Friday), onde as requisições GET/PUT esgotem os recursos de CPU ou o limite de conexões do banco.

### Investigação de Incidente (Pedido #17 e Atualização Assíncrona)

1. **O pedido foi criado?** Sim, gravado no banco.
2. **O estoque foi reservado?** Sim.
3. **O evento pedido.criado foi publicado?** Sim.
4. **O Pagamento Service recebeu o evento?** Sim.
5. **O pagamento foi processado?** Não. A operação lançou uma exceção por indisponibilidade simulada do Gateway de pagamentos.
6. **Em qual etapa ocorreu o problema?** No processamento assíncrono interno do `Pagamento Service`, após ter recebido a mensagem com sucesso do RabbitMQ.
7. **Qual evidência nos logs permite identificar a falha?** Nos logs do `pagamento-service`, encontra-se a mensagem de erro com o mesmo `correlationId`: `ERRO ao processar pagamento do pedido 17: gateway de pagamento indisponivel`, confirmando que o erro foi isolado na lógica de pagamento.
8. **O que aconteceu com a mensagem no RabbitMQ?** Após esgotar as tentativas de retry configuradas pelo Spring AMQP, a mensagem foi encaminhada para a Dead Letter Queue (`pedido.criado.dlq`), onde pode ser inspecionada e reprocessada manualmente.
