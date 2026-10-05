# microservices-lab

Plataforma de e-commerce com 3 microsserviços (Java 17 + Spring Boot 3.3), PostgreSQL por serviço e RabbitMQ.

| Serviço | Porta no host | Banco | Papel |
|---|---|---|---|
| pedido-service | 8080 | pedido-db | API de pedidos; chama o Estoque via REST; publica `pedido.criado`; consome `pagamento.processado` |
| estoque-service | 8081 | estoque-db | Consulta e reserva de estoque |
| pagamento-service | — (só rede interna) | pagamento-db | Consome `pedido.criado`, aprova ~80% / rejeita ~20%, publica `pagamento.processado` |
| rabbitmq | 5672 / 15672 | — | Painel: http://localhost:15672 (guest / guest) |

Requisito: Docker Desktop (ou Docker Engine + Compose). Não é preciso ter Java/Maven instalados — o build acontece dentro do Docker.

## Autores

| Estudante | Matrícula |
|---|---|
| Matheus Augusto Ferreira Medeiros | 202305532 |
| Marcello Ronald José da Silva | 202302618 |

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

# Roteiro de testes (com o que printar)

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

Prints: resposta 201 do POST (com o header `X-Correlation-Id`), estoque antes/depois, logs dos 3 serviços filtrados pelo correlationId, `select` da tabela pagamento.

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
