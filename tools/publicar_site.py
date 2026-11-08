"""Monta o site estático inteiro num comando (Rodada 11.b, parte 4; docs/rodada-11b.md). Não publica nada.

    python tools/publicar_site.py

1. confere se o banco exportado existe e é do patch atual (senão: `cd backend && ./mvnw -Pexport`);
2. copia o banco para uma pasta temporária e sobe o app local completo sobre a cópia (o app normal grava ao subir:
   migrações e sementes; o banco exportado nunca muda), gera os JSONs (tools/site_estatico.py) e desliga o app;
3. faz o build estático do frontend (`npm run build:estatico`);
4. monta `site-publico/` com a página, `api/` e `api/manifest.json`;
5. confere o manifesto contra os arquivos e imprime o tamanho, a contagem e a folga no GitHub Pages.

Depois: copie o conteúdo de `site-publico/` para o repositório do site e dê push (passo a passo no doc).
Só a biblioteca padrão do Python. Precisa de Java 11+ e Node 18+ (os mesmos do app).
"""

import argparse
import json
import os
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import site_estatico  # noqa: E402

#: GitHub Pages (docs.github.com, "GitHub Pages limits", conferido em 2026-11-07): site publicado de até 1 GB,
#: repositório de origem com limite recomendado de 1 GB, banda "soft" de 100 GB por mês, deploy expira em 10 minutos;
#: o limite de 10 builds por hora não vale para um workflow próprio do Actions (o nosso). Acima disso, 429.
#: O teto deste site fica na metade, por folga: 500 MB.
PAGES_SITE_LIMIT = 1024 ** 3
SITE_CAP = 500 * 1024 ** 2
PAGES_BANDWIDTH_MONTH = 100 * 1024 ** 3
#: O git do GitHub recusa um arquivo acima de 100 MiB (acima de 50 MiB, só avisa).
GIT_FILE_LIMIT = 100 * 1024 ** 2


def current_patch():
    """O patch dos dados do jogo que o app lê (application.properties: campeoes_<patch>.yml)."""
    with open(os.path.join(ROOT, 'backend', 'src', 'main', 'resources', 'application.properties'), encoding='utf-8') as f:
        m = re.search(r'planner\.seed\.champions=classpath:seed/campeoes_([0-9a-z_]+)\.yml', f.read())
    return m.group(1).replace('_', '.') if m else None


def export_info(db):
    """O registro da exportação dentro do banco (tabela public_export): data, patch, pastas."""
    con = sqlite3.connect(f'file:{db}?mode=ro', uri=True)
    try:
        return dict(con.execute('SELECT key, value FROM public_export').fetchall())
    except sqlite3.Error:
        return None
    finally:
        con.close()


def start_app(db, port, log_path):
    """Sobe o app local completo (perfil normal) sobre o banco dado, numa porta só dele."""
    env = dict(os.environ, SERVER_PORT=str(port), PLANNER_DB_PATH=db, SPRING_PROFILES_ACTIVE='',
               PLANNER_SITE_DIR=os.path.join(ROOT, 'site-publico'))
    mvnw = os.path.join(ROOT, 'backend', 'mvnw.cmd' if os.name == 'nt' else 'mvnw')
    log = open(log_path, 'w', encoding='utf-8')
    kw = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
    proc = subprocess.Popen([mvnw, '-q', 'spring-boot:run'], cwd=os.path.join(ROOT, 'backend'), env=env,
                            stdout=log, stderr=subprocess.STDOUT, **kw)
    return proc, log


def stop_app(proc):
    if proc.poll() is not None:
        return
    if os.name == 'nt':
        subprocess.run(['taskkill', '/T', '/F', '/PID', str(proc.pid)], capture_output=True)
    else:
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()


def wait_up(url, proc, seconds=420):
    t0 = time.time()
    while time.time() - t0 < seconds:
        if proc.poll() is not None:
            return False
        try:
            with urllib.request.urlopen(url, timeout=5) as r:
                if r.status == 200:
                    return True
        except OSError:
            pass
        time.sleep(3)
    return False


def build_frontend(base):
    npm = shutil.which('npm.cmd' if os.name == 'nt' else 'npm') or shutil.which('npm')
    if not npm:
        sys.exit('npm não encontrado: instale o Node 18+ (nesta máquina: `nvm use 24`).')
    env = dict(os.environ)
    if base:
        env['VITE_BASE'] = base
    subprocess.run([npm, 'run', 'build:estatico'], cwd=os.path.join(ROOT, 'frontend'), env=env, check=True)


def mb(n):
    return f'{n / 1024 / 1024:.1f} MB'


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--banco', default=os.path.join(ROOT, 'backend', 'data', 'publico', 'ornn-planner-publico.db'),
                    help='o banco exportado (./mvnw -Pexport)')
    ap.add_argument('--saida', default=os.path.join(ROOT, 'site-publico'), help='a pasta do site montado')
    ap.add_argument('--porta', type=int, default=8097, help='porta do app local durante a geração')
    ap.add_argument('--api', help='usar um app já rodando (sobre uma cópia do banco exportado) em vez de subir um')
    ap.add_argument('--base', default='', help='a pasta do site no GitHub Pages ("/meu-repo/"); vazio = caminhos relativos')
    ap.add_argument('--campeoes', default='', help='só estes códigos (ORNN,DARIUS)')
    ap.add_argument('--sem-icones', action='store_true')
    ap.add_argument('--sem-lutas', action='store_true')
    ap.add_argument('--sem-ritmos', action='store_true')
    ap.add_argument('--rapido', action='store_true', help='sem ofensiva e habilidades')
    ap.add_argument('--sem-frontend', action='store_true', help='não refaz o build da página (usa o dist-estatico que houver)')
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    t0 = time.time()

    # 1. the exported database, of the current patch
    if not os.path.isfile(a.banco):
        sys.exit(f'Banco exportado não encontrado: {a.banco}\nExporte antes: cd backend && ./mvnw -Pexport')
    info = export_info(a.banco)
    patch = current_patch()
    if not info:
        sys.exit(f'{a.banco} não é uma exportação pública (sem public_export). Exporte com ./mvnw -Pexport.')
    if patch and info.get('patch') != patch:
        sys.exit(f"O banco exportado é do patch {info.get('patch')}, e o app está no {patch}: exporte de novo.")
    print(f"1. Banco exportado: patch {info.get('patch')}, em {info.get('exported_at')}, pastas {info.get('folders')}")

    # 2. the full app over a copy, the JSONs, the app stopped
    work = tempfile.mkdtemp(prefix='site-publico-')
    proc = log = None
    try:
        api = a.api
        if not api:
            copy = os.path.join(work, 'copia.db')
            shutil.copyfile(a.banco, copy)
            log_path = os.path.join(work, 'app.log')
            print(f'2. Subindo o app local sobre uma cópia ({copy}), porta {a.porta}… (log: {log_path})')
            proc, log = start_app(copy, a.porta, log_path)
            api = f'http://localhost:{a.porta}'
            if not wait_up(api + '/api/site/info', proc):
                sys.exit(f'O app não subiu: veja {log_path}')
        if os.path.isdir(a.saida):
            shutil.rmtree(a.saida)
        t1 = time.time()
        args = ['--api', api, '--saida', a.saida]
        for flag in ('sem_icones', 'sem_lutas', 'sem_ritmos', 'rapido'):
            if getattr(a, flag):
                args.append('--' + flag.replace('_', '-'))
        if a.campeoes:
            args += ['--campeoes', a.campeoes]
        manifest = site_estatico.main(args)
        gen = time.time() - t1
    finally:
        if proc:
            stop_app(proc)
            log.close()
    print(f'   JSONs gerados em {gen:.0f} s; app desligado.')

    # 3. the page
    if not a.sem_frontend:
        print('3. Build estático do frontend…')
        build_frontend(a.base)
    dist = os.path.join(ROOT, 'frontend', 'dist-estatico')
    if not os.path.isfile(os.path.join(dist, 'index.html')):
        sys.exit('Sem frontend/dist-estatico: rode sem --sem-frontend.')

    # 4. the folder: the page, api/ and the manifest; .nojekyll so Pages serves every file as it is
    shutil.copytree(dist, a.saida, dirs_exist_ok=True)
    open(os.path.join(a.saida, '.nojekyll'), 'w').close()
    shutil.rmtree(work, ignore_errors=True)

    # 5. the check and the numbers
    problems = site_estatico.verificar(a.saida)
    n, total = site_estatico.tamanho(a.saida)
    biggest = max((os.path.getsize(os.path.join(r, f)), os.path.relpath(os.path.join(r, f), a.saida))
                  for r, _, fs in os.walk(a.saida) for f in fs)
    print(f"4. Site em {a.saida}: {n} arquivos, {mb(total)} ({len(manifest['champions'])} campeões, "
          f"{sum(c['builds'] for c in manifest['champions'])} builds), em {time.time() - t0:.0f} s no total.")
    print(f'   Maior arquivo: {biggest[1]} ({mb(biggest[0])}; o git do GitHub recusa acima de {mb(GIT_FILE_LIMIT)}).')
    print(f'   GitHub Pages: {100 * total / PAGES_SITE_LIMIT:.1f}% do limite de 1 GB; teto deste site 500 MB '
          f'(folga {mb(SITE_CAP - total)}). Banda de 100 GB/mês ≈ {PAGES_BANDWIDTH_MONTH // max(1, total):,} '
          'downloads do site inteiro (uma visita baixa só o que abre).')
    if total > SITE_CAP:
        print('   ATENÇÃO: acima do teto de 500 MB. Use --sem-ritmos, --rapido ou --sem-icones, ou menos campeões.')
    if problems:
        print('5. Problemas:')
        for p in problems:
            print('   -', p)
        return 1
    print('5. Manifesto confere com os arquivos. Agora copie site-publico/ para o repositório do site e dê push.')
    with open(os.path.join(a.saida, 'api', 'manifest.json'), encoding='utf-8') as f:
        json.load(f)
    return 0


if __name__ == '__main__':
    sys.exit(main())
