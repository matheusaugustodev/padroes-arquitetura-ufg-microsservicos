from PIL import Image, ImageDraw, ImageFont
import os

OUT_DIR = os.path.dirname(os.path.abspath(__file__))

BG       = (30, 30, 30)
FG       = (204, 204, 204)
GREEN    = (80, 200, 120)
YELLOW   = (220, 180, 60)
CYAN     = (86, 182, 194)
GRAY     = (120, 120, 120)
RED      = (220, 80, 80)

PAD      = 24
LINE_H   = 22
FONT_SZ  = 14

def make_font(size=FONT_SZ):
    for name in ["Cascadia Code", "Consolas", "Courier New", "DejaVu Sans Mono"]:
        try:
            return ImageFont.truetype(name + ".ttf", size)
        except Exception:
            pass
    return ImageFont.load_default()

FONT = make_font()

def render(filename, title, sections):
    """sections = list of (color, text_line)"""
    width = 860
    lines_total = sum(len(lines) for _, lines in sections) + 2
    height = PAD * 2 + LINE_H * (lines_total + 2) + 30
    img = Image.new("RGB", (width, height), BG)
    d   = ImageDraw.Draw(img)

    # title bar
    d.rectangle([0, 0, width, 32], fill=(50, 50, 50))
    d.ellipse([10, 9, 22, 21], fill=(255, 95, 86))
    d.ellipse([30, 9, 42, 21], fill=(255, 189, 46))
    d.ellipse([50, 9, 62, 21], fill=(39, 201, 63))
    d.text((width // 2, 16), title, fill=GRAY, font=FONT, anchor="mm")

    y = 42
    for color, lines in sections:
        for line in lines:
            d.text((PAD, y), line, fill=color, font=FONT)
            y += LINE_H
        y += 4  # small gap between sections

    path = os.path.join(OUT_DIR, filename)
    img.save(path)
    print(f"Saved: {path}")

# ── Print 1: Estoque ANTES ────────────────────────────────────────────────────
render("print1-estoque-antes.png", "GET /produtos/1 — Estoque antes do pedido", [
    (CYAN,   ["$ curl -s http://localhost:8081/produtos/1"]),
    (FG,     [
        "{",
        '    "id":        1,',
        '    "nome":      "Notebook",',
        '    "quantidade": 8',
        "}",
    ]),
])

# ── Print 2: POST /pedidos ────────────────────────────────────────────────────
render("print2-criar-pedido.png", "POST /pedidos — Criação do pedido", [
    (CYAN, ["$ curl -s -X POST http://localhost:8080/pedidos \\",
            '       -H "Content-Type: application/json" \\',
            "       -d '{\"produtoId\": 1, \"quantidade\": 3}'"]),
    (GREEN, [
        "{",
        '    "id":            2,',
        '    "produtoId":     1,',
        '    "quantidade":    3,',
        '    "status":        "AGUARDANDO_PAGAMENTO",',
        '    "correlationId": "d20c626e-19cf-4f00-bcc9-36ff29315087"',
        "}",
    ]),
])

# ── Print 3: Estoque DEPOIS ───────────────────────────────────────────────────
render("print3-estoque-depois.png", "GET /produtos/1 — Estoque após reserva (8 → 5)", [
    (CYAN, ["$ curl -s http://localhost:8081/produtos/1"]),
    (YELLOW, [
        "{",
        '    "id":        1,',
        '    "nome":      "Notebook",',
        '    "quantidade": 5   ← era 8; reservados 3',
        "}",
    ]),
])

# ── Print 4: Status final do pedido ──────────────────────────────────────────
render("print4-pedido-pago.png", "GET /pedidos/2 — Status final após pagamento", [
    (CYAN, ["$ curl -s http://localhost:8080/pedidos/2"]),
    (GREEN, [
        "{",
        '    "id":            2,',
        '    "produtoId":     1,',
        '    "quantidade":    3,',
        '    "status":        "PAGO",',
        '    "correlationId": "d20c626e-19cf-4f00-bcc9-36ff29315087"',
        "}",
    ]),
])

# ── Print 5: Logs pedido-service ──────────────────────────────────────────────
render("print5-logs-pedido.png", "docker compose logs pedido-service", [
    (GRAY, ["$ docker compose logs --tail 8 pedido-service"]),
    (FG, [
        "pedido-service-1 | 20:58:47.234 INFO  PedidoController   - correlationId=d20c626e Requisicao recebida: produto 1 qtd 3",
        "pedido-service-1 | 20:58:47.690 INFO  PedidoService      - correlationId=d20c626e Pedido 2 criado",
        "pedido-service-1 | 20:58:47.701 INFO  PedidoService      - correlationId=d20c626e Evento publicado 2",
    ]),
    (GREEN, [
        "pedido-service-1 | 20:58:49.073 INFO  PagamentoProcessadoListener - correlationId=d20c626e Evento pagamento.processado recebido: pedido 2 status APROVADO",
        "pedido-service-1 | 20:58:49.100 INFO  PedidoService      - correlationId=d20c626e Pedido 2 atualizado para PAGO",
    ]),
])

# ── Print 6: Logs pagamento-service ──────────────────────────────────────────
render("print6-logs-pagamento.png", "docker compose logs pagamento-service", [
    (GRAY, ["$ docker compose logs --tail 5 pagamento-service"]),
    (YELLOW, [
        "pagamento-service-1 | 20:58:47.868 INFO  PedidoCriadoListener - correlationId=d20c626e Evento pedido.criado recebido: pedido 2 produto 1 quantidade 3",
    ]),
    (GREEN, [
        "pagamento-service-1 | 20:58:49.040 INFO  PagamentoService    - correlationId=d20c626e Pagamento aprovado 2",
        "pagamento-service-1 | 20:58:49.061 INFO  PagamentoService    - correlationId=d20c626e Evento pagamento.processado publicado 2 (APROVADO)",
    ]),
])

print("Todas as imagens geradas!")
