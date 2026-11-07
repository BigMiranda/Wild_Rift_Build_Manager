"""Traça a grade de paredes do mapa a partir do print do minimapa (Rodada 10, Parte 1).

Uso: python tools/mapa_minimapa.py capturas/7.3d/mapa/minimapa-base.png [--png saida.png]

Escala e calibragem (documentadas em docs/rodada-10.md):
  - o centro do mapa é o ponto médio entre os dois Nexos no print: (166,5; 156) px;
  - 40 unidades da Arena por pixel: o quadro jogável do print tem cerca de 298 px de lado, e o mapa segue com 12.000
    unidades (o tamanho de antes, também estimativa);
  - x da Arena cresce para a direita no print; y da Arena cresce para cima (o print tem y para baixo).

Cada célula de 200 unidades (5 px) é parede quando 40% ou mais dos seus pixels são escuros (os blocos pretos do
minimapa); uma parede de célula só, sem vizinha, é tirada como ruído. O lado azul (x + y < tamanho) vale; o
vermelho é o espelho pelo centro, como no resto do mapa. A base azul
(o arco claro), as rotas (ROUTES) e os pontos de acampamento são sempre livres. Imprime as linhas de `paredes_grade` do
mapa_<patch>.yml, de cima (y alto) para baixo, com '#' para parede e '.' para chão.
"""
import sys

from PIL import Image

CENTER = (166.5, 156.0)
UNITS_PER_PX = 40.0
SIZE = 12000
CELL = 200
N = SIZE // CELL
FRAME = (20, 8, 315, 300)          # quadro jogável no print: esquerda, cima, direita, baixo
BASE_ARC = ((20, 305), 82)         # o arco claro da base azul: centro e raio, em px
# as rotas do mapa_<patch>.yml: o print mostra as faixas abertas, e os ícones de torre e de tropa por cima delas viram
# parede falsa; cada rota fica livre numa faixa de LANE_HALF de cada lado
ROUTES = [
    [(1260, 1280), (800, 2800), (800, 10300), (1700, 11200), (9200, 11200), (10740, 10720)],
    [(1260, 1280), (2340, 2200), (3400, 3300), (4620, 4750), (6000, 6000), (7380, 7250), (8600, 8700), (9660, 9800), (10740, 10720)],
    [(1260, 1280), (2800, 800), (10300, 800), (11200, 1700), (11200, 9200), (10740, 10720)],
]
LANE_HALF = 250
FREE = [(2900, 6160), (6260, 2440), (1620, 6840), (2540, 4640), (5620, 3720), (7820, 1840)]


def px(x, y):
    return CENTER[0] + (x - SIZE / 2) / UNITS_PER_PX, CENTER[1] - (y - SIZE / 2) / UNITS_PER_PX


def dark(p):
    r, g, b = p
    m = (r + g + b) / 3
    return m < 36 and not (g - r > 12 and m > 28)


def wall(im, cx, cy):
    x0, y1 = px(cx * CELL, cy * CELL)
    x1, y0 = px((cx + 1) * CELL, (cy + 1) * CELL)
    total = hits = 0
    for yy in range(int(round(y0)), int(round(y1))):
        for xx in range(int(round(x0)), int(round(x1))):
            total += 1
            out = xx < FRAME[0] or xx > FRAME[2] or yy < FRAME[1] or yy > FRAME[3]
            (bx, by), r = BASE_ARC
            if not out and (xx - bx) ** 2 + (yy - by) ** 2 <= r * r:
                continue
            if out or dark(im.getpixel((xx, yy))):
                hits += 1
    return total > 0 and hits * 5 >= total * 2


def seg_dist(x, y, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
    return ((x - ax - t * dx) ** 2 + (y - ay - t * dy) ** 2) ** 0.5


def grid(path):
    im = Image.open(path).convert('RGB')
    g = [[False] * N for _ in range(N)]
    for cy in range(N):
        for cx in range(N):
            if cx + cy < N - 1:
                g[cy][cx] = wall(im, cx, cy)
            elif cx + cy == N - 1:
                g[cy][cx] = wall(im, cx, cy) and wall(im, N - 1 - cx, N - 1 - cy)
    # a parede de uma célula só, sem vizinha, é ruído do print (contorno de ícone)
    lone = [(cx, cy) for cy in range(1, N - 1) for cx in range(1, N - 1) if g[cy][cx]
            and not any(g[cy + dy][cx + dx] for dy in (-1, 0, 1) for dx in (-1, 0, 1) if dx or dy)]
    for cx, cy in lone:
        g[cy][cx] = False
    for route in ROUTES:
        for (ax, ay), (bx, by) in zip(route, route[1:]):
            for cy in range(N):
                for cx in range(N):
                    if seg_dist((cx + 0.5) * CELL, (cy + 0.5) * CELL, ax, ay, bx, by) <= LANE_HALF:
                        g[cy][cx] = False
    for x, y in FREE:
        cx, cy = int(x // CELL), int(y // CELL)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                g[cy + dy][cx + dx] = False
    for cy in range(N):
        for cx in range(N):
            if cx + cy > N - 1:
                g[cy][cx] = g[N - 1 - cy][N - 1 - cx]
            elif cx + cy == N - 1:   # on the diagonal both cells of a pair are walls only if both were
                g[cy][cx] = g[N - 1 - cy][N - 1 - cx] = g[cy][cx] and g[N - 1 - cy][N - 1 - cx]
    return g


def main():
    g = grid(sys.argv[1])
    rows = [''.join('#' if g[cy][cx] else '.' for cx in range(N)) for cy in range(N - 1, -1, -1)]
    for r in rows:
        print('    - "' + r + '"')
    if '--png' in sys.argv:
        out = Image.new('RGB', (N * 8, N * 8), (110, 100, 110))
        for cy in range(N):
            for cx in range(N):
                if g[cy][cx]:
                    for yy in range(8):
                        for xx in range(8):
                            out.putpixel((cx * 8 + xx, (N - 1 - cy) * 8 + yy), (0, 0, 0))
        out.save(sys.argv[sys.argv.index('--png') + 1])


if __name__ == '__main__':
    main()
