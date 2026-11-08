"""Lê as moitas do mapa pela tela "Guia do Rift" do jogo (Rodada 12).

Uso:
  python tools/mapa_guia.py capturas/7.3d/guia-rift/mapa/normal_nov.png capturas/7.3d/guia-rift/mapa/normal_tudo.png
         [--png saida.png] [--plano plano.png] [--paredes paredes.png]

A tela do guia (botão "i" da seleção de modo) mostra o mapa inteiro numa vista 3D inclinada, bem mais nítida que o
minimapa: dá para ver as moitas, as plantas e as bordas das paredes. O print é do celular, 2756 × 1268 px, com o zoom
no mínimo (a barra vertical ao lado do mapa no topo). A leitura:

1. Endireita a vista por uma homografia (PONTOS): dez torres, cinco pares que se espelham pelo centro. Cada par foi
   lido no mapa endireitado e levado à média simétrica, de modo que o mapa saia simétrico pelo centro. O referencial
   é o do mapa_<patch>.yml (12.000 unidades, origem no canto da base azul, y para cima); docs/rodada-12.md tem a conta.
2. Junta os dois prints: o da aba "Novidades" tem só um balão por cima (a Zona de Vento), o de "Tudo" tem os
   hexágonos dos marcadores; onde um tem enfeite, vale o outro.
3. Marca como moita cada pixel de capim alto (verde-azulado claro, ver `bush`), numa grade de 100 unidades; tira o
   que cai em parede da paredes_grade e as células soltas.
4. Junta o lado azul (x + y < 12.000) com o espelho do vermelho, porque os marcadores e o balão tapam lugares
   diferentes nos dois lados, e cobre cada mancha com círculos de até RMAX de raio.

Imprime as linhas de `moitas:` do mapa_<patch>.yml, só do lado azul (o RiftMap espelha). --png desenha o mapa
endireitado com os círculos por cima; --plano salva só o mapa endireitado (10 unidades por pixel).
"""
import sys

import numpy as np
from PIL import Image, ImageDraw

SIZE = 12000
UPP = 10            # unidades por pixel do mapa endireitado
CELL = 100          # célula da grade de moitas
RMAX = 220          # raio máximo de um círculo de moita
# (x, y) no mapa -> (u, v) no print de 2756 x 1268: torres de base, externas e internas das rotas laterais
PONTOS = [
    ((750, 2925), (680, 842)), ((11250, 9075), (1997, 316)),
    ((2880, 885), (946, 1073)), ((9120, 11115), (1720, 174)),
    ((705, 8600), (776, 337)), ((11295, 3400), (2137, 798)),
    ((600, 5170), (696, 616)), ((11400, 6830), (2052, 483)),
    ((8485, 770), (1791, 1099)), ((3515, 11230), (1111, 165)),
]
# o balão da aba Novidades e o pino dele, no print (esquerda, cima, direita, baixo)
BALAO = [(560, 415, 1365, 620), (1385, 600, 1440, 690)]
# a barra de zoom à direita do mapa e o texto da legenda embaixo
FAIXAS = [(2140, 150, 2180, 1080), (1250, 1050, 1640, 1110)]


def homography(pairs):
    a = []
    for (x, y), (u, v) in pairs:
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y, -u])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y, -v])
    _, _, vt = np.linalg.svd(np.array(a, float))
    h = vt[-1].reshape(3, 3)
    return h / h[2, 2]


H = homography(PONTOS)


def to_screen(x, y):
    p = H @ np.array([x, y, 1.0])
    return p[0] / p[2], p[1] / p[2]


def plane(im):
    """O print endireitado: pixel (i, j) é o ponto (i * UPP, SIZE - j * UPP) do mapa."""
    n = SIZE // UPP
    s = np.array([[UPP, 0, 0], [0, -UPP, SIZE], [0, 0, 1]], float)
    m = H @ s
    m = m / m[2, 2]
    return im.transform((n, n), Image.PERSPECTIVE, tuple(m.flatten()[:8]), Image.BICUBIC)


def merged(nov, tudo):
    """O print de Novidades com o balão trocado pelo pedaço do de Tudo."""
    out = nov.copy()
    for box in BALAO:
        out.paste(tudo.crop(box), box[:2])
    return out


def overlay_mask(size):
    m = Image.new('L', size, 0)
    d = ImageDraw.Draw(m)
    for box in FAIXAS:
        d.rectangle(box, fill=255)
    return m


def bush(a):
    """Capim alto: verde-azulado, com o verde bem acima do vermelho e perto do azul (o rio tem mais azul que verde; o
    chão de terra, mais vermelho). Sozinha a cor também pega copa de árvore e chão de grama iluminado, por isso a
    leitura parte das SEMENTES e não da máscara inteira."""
    r, g, b = a[..., 0].astype(int), a[..., 1].astype(int), a[..., 2].astype(int)
    return (g > 78) & (g - r > 33) & (abs(g - b) < 15)


# Uma semente por moita, lida no mapa endireitado (--plano) e conferida pelo par espelhado do outro lado. Pode estar
# em qualquer lado; a moita sai sempre do lado azul. Lista em docs/rodada-12.md.
SEMENTES = [
    (1900, 11250), (1325, 10625), (775, 10025),         # canto da rota do Barão
    (450, 6575),                                        # rota do Barão, junto da parede
    (5375, 11550),                                      # rota do Barão (lado de cima), na metade
    (4700, 10400),                                      # diante da parede do rio de cima
    (5250, 8875), (6800, 8825),                         # entre o rio de cima e os acampamentos vermelhos
    (4325, 7300),                                       # rio de cima, no meio da água
    (3800, 5350),                                       # entrada da selva azul pelo meio
    (5225, 3350),                                       # faixa de capim ao lado da rota do meio
    (6725, 5050),                                       # rio de baixo, ao lado do Frutomel
    (2825, 5725),                                       # junto do Azuporã azul
    (6350, 2300), (8350, 2725),                         # abaixo do Rubrivira azul; junto dos Krugues
    (7275, 1625),                                       # diante da parede da rota do Dragão
    (9850, 950), (10900, 1650),                         # rota do Dragão (a da metade fica sob a legenda: vem do espelho)
]
REACH = 450         # até onde uma moita cresce a partir da semente
FILL = 0.4          # fração de capim para uma célula de CELL contar
RSOMBRA = 200       # raio de uma moita que a cor não alcança


def bush_grid(flat):
    """Fração de capim por célula de CELL, na grade (linha 0 = y baixo)."""
    a = np.asarray(flat.convert('RGB'))
    hit = bush(a)
    k = CELL // UPP
    n = SIZE // CELL
    g = hit[: n * k, : n * k].reshape(n, k, n, k).mean(axis=(1, 3))
    return g[::-1, :]


def grow(frac, x, y):
    """As células de capim ligadas à semente, até REACH dela; a semente vale mesmo se a célula dela for rala."""
    n = frac.shape[0]
    sx, sy = int(x // CELL), int(y // CELL)
    # a semente cai na célula mais cheia de capim em volta dela (um clique de 100 unidades de folga)
    best = max(((sx + dx, sy + dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1)), key=lambda c: frac[c[1], c[0]])
    stack, seen = [best], {best}
    while stack:
        cx, cy = stack.pop()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                u, v = cx + dx, cy + dy
                if (u, v) in seen or not (0 <= u < n and 0 <= v < n) or frac[v, u] < FILL:
                    continue
                if ((u + 0.5) * CELL - x) ** 2 + ((v + 0.5) * CELL - y) ** 2 > REACH * REACH:
                    continue
                seen.add((u, v))
                stack.append((u, v))
    return sorted(seen)


def circles(cells):
    """Cobre uma mancha com círculos: corta-a em pedaços de 1,6 * RMAX ao longo do eixo maior."""
    pts = np.array([((x + 0.5) * CELL, (y + 0.5) * CELL) for x, y in cells])
    c = pts.mean(axis=0)
    axis = np.linalg.svd(pts - c)[2][0] if len(pts) > 2 else np.array([1.0, 0.0])
    t = (pts - c) @ axis
    parts = max(1, int(np.ceil((t.max() - t.min()) / (1.6 * RMAX))))
    edges = np.linspace(t.min(), t.max() + 1e-6, parts + 1)
    out = []
    for i in range(parts):
        sel = pts[(t >= edges[i]) & (t < edges[i + 1])]
        if len(sel) == 0:
            continue
        m = sel.mean(axis=0)
        r = min(RMAX, max(120.0, np.sqrt(((sel - m) ** 2).sum(axis=1)).max() + CELL * 0.5))
        out.append([m[0], m[1], r])
    return out


def blue_side(x, y):
    return (x, y) if x + y <= SIZE else (SIZE - x, SIZE - y)


def read(nov_path, tudo_path):
    nov = Image.open(nov_path).convert('RGB')
    tudo = Image.open(tudo_path).convert('RGB')
    shot = merged(nov, tudo)
    shot.paste((0, 0, 0), mask=overlay_mask(shot.size))     # a barra de zoom e a legenda viram "não capim"
    flat = plane(shot)
    frac = bush_grid(flat)
    found, notes = [], []
    for sx, sy in SEMENTES:
        cells = grow(frac, sx, sy)
        if len(cells) < 2:
            # moita na sombra (no rio, sob a copa): a cor não chega ao limiar; fica um círculo do tamanho típico
            notes.append('semente (%d, %d): quase sem capim em volta, círculo de %d' % (sx, sy, RSOMBRA))
            spots = [[sx, sy, RSOMBRA]]
        else:
            spots = circles(cells)
        for cx, cy, r in spots:
            bx, by = blue_side(cx, cy)
            # um círculo que já existe (vindo do par espelhado) não se repete
            if any((bx - fx) ** 2 + (by - fy) ** 2 < (0.6 * max(r, fr)) ** 2 for fx, fy, fr in found):
                continue
            found.append((bx, by, r))
    found = [(int(round(x / 10) * 10), int(round(y / 10) * 10), int(round(r / 10) * 10)) for x, y, r in found]
    found.sort(key=lambda b: (-b[1], b[0]))
    return flat, found, notes


def main():
    flat, found, notes = read(sys.argv[1], sys.argv[2])
    for x, y, r in found:
        print('  - {x: %d, y: %d, raio: %d}' % (x, y, r))
    for n in notes:
        print('# ' + n, file=sys.stderr)
    if '--plano' in sys.argv:
        flat.save(sys.argv[sys.argv.index('--plano') + 1])
    if '--png' in sys.argv:
        out = flat.copy()
        d = ImageDraw.Draw(out)
        for x, y, r in found:
            for px, py in ((x, y), (SIZE - x, SIZE - y)):
                d.ellipse([(px - r) / UPP, (SIZE - py - r) / UPP, (px + r) / UPP, (SIZE - py + r) / UPP],
                          outline=(255, 60, 60), width=2)
        for x, y in SEMENTES:
            d.point([(x / UPP, (SIZE - y) / UPP)], fill=(255, 255, 0))
        out.save(sys.argv[sys.argv.index('--png') + 1])
    if '--paredes' in sys.argv:
        walls_overlay(flat).save(sys.argv[sys.argv.index('--paredes') + 1])


def walls_overlay(flat, seed='backend/src/main/resources/seed/mapa_7_3d.yml'):
    """O mapa endireitado com as `paredes:` do seed (e o espelho de cada uma) por cima, para conferir o traçado à mão
    (Rodada 12, parte 3: os polígonos da metade de baixo, onde o print é mais nítido)."""
    import yaml
    m = yaml.safe_load(open(seed, encoding='utf-8'))
    im = flat.convert('RGBA')
    ov = Image.new('RGBA', im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for p in m.get('paredes') or []:
        for q in (p, [(SIZE - x, SIZE - y) for x, y in p]):
            d.polygon([(x / UPP, (SIZE - y) / UPP) for x, y in q], fill=(255, 0, 0, 70), outline=(255, 40, 40, 255))
    return Image.alpha_composite(im, ov).convert('RGB')


if __name__ == '__main__':
    main()
