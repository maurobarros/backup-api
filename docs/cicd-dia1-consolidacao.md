# CI/CD — Dia 1 de Consolidação

Documento de apoio à transição para DevOps. Resume o que ficou pronto hoje no repositório `backup-api`: setup do GitHub, primeiro pipeline de CI, branch protection, fluxo de trabalho com Pull Requests.

---

## 1. Mapa conceptual — CI/CD em duas frases

**CI (Continuous Integration)** — cada push dispara automaticamente *checkout → build → testes*. Detetar problemas em minutos, não em dias.

**CD (Continuous Delivery vs Deployment)** — *Delivery* gera um artefacto pronto a entrar em produção (deploy manual). *Deployment* põe automaticamente em produção quando o CI passa.

### Princípios que guiam tudo

| Princípio | O que significa |
|---|---|
| **Imutabilidade da imagem** | Imagem construída e taggeada (ex.: `abc123`) nunca muda. Bug → nova imagem com nova tag. Nunca `latest` em produção. |
| **Idempotência** | Correr o pipeline 10x dá o mesmo resultado. Sem efeitos colaterais. |
| **Separação build vs deploy** | Build acontece uma vez; deploy promove o mesmo artefacto entre ambientes. |
| **Pipeline as Code** | O pipeline vive no Git, em `.github/workflows/`. Versionado, revisado em PR. Sem cliques em UIs. |
| **GitOps** | O estado desejado do cluster vive num repo Git. Um agente (ArgoCD, Flux) puxa e aplica. |

---

## 2. O que ficou pronto hoje

### Repositório no GitHub

- Repo público: `github.com/maurobarros/backup-api`.
- `.gitignore` reforçado: exclui `backup-api-key.json`, `fake-gcs-data/`, `target/`, `.DS_Store`.
- Primeiro commit fundador com o stack completo (Dockerfile, k8s/, src/, pom.xml, docs/).

### Workflow de CI — `.github/workflows/ci.yml`

```yaml
name: CI
on:
  push:        { branches: [main] }
  pull_request: { branches: [main] }

jobs:
  test:
    name: Build & Test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - run: ./mvnw -B test
```

### Spring Profiles para testes em CI

Problema diagnosticado: o `BackupApiApplicationTests` faz `@SpringBootTest` (carrega contexto inteiro) e tenta ligar ao Postgres. No runner do GitHub não há Postgres → falha.

Solução aplicada:

1. **`pom.xml`** — adicionada dependência H2 em `<scope>test</scope>` (só no classpath durante testes).
2. **`src/test/resources/application-test.properties`** — datasource em memória com dialeto compatível com Postgres.
3. **`@ActiveProfiles("test")`** no teste — força o Spring a usar o profile `test`.

> **Princípio em ação:** *mesmo código, configuração diferente por ambiente* (12-factor app).

---

## 3. Branch protection + Fluxo com Pull Requests

### A regra ativa em `main`

- Require pull request before merging.
- Require status checks to pass — `Build & Test`.
- Require branches to be up to date.
- Do not allow bypassing — **mesmo o owner não escapa**.

### O que aconteceu na prática

```
git push (direto para main)  →  remote rejected
                                 "Required status check Build & Test is expected"
```

A partir daqui, **toda** a alteração tem de passar por PR.

### O fluxo correto

```
1. git switch -c feature/algo        ← branch nova a partir de main
2. ... commits locais ...
3. git push -u origin feature/algo   ← branch para o remoto
4. Abrir PR no GitHub
5. CI corre → ✅ verde
6. Merge na UI do GitHub
7. Apagar branch (local + remota)
```

---

## 4. Local vs Remote — o modelo mental

```
         ┌──────────────────────────┐
         │      WORKING DIR         │  ← onde editas os ficheiros
         └──────────┬───────────────┘
              git add
                    ▼
         ┌──────────────────────────┐
         │      STAGING AREA        │  ← snapshot pré-commit
         └──────────┬───────────────┘
              git commit
                    ▼
         ┌──────────────────────────┐
         │   REPO LOCAL (.git/)     │  ← histórico no teu disco
         └──────────┬───────────────┘
              git push
                    ▼
         ┌──────────────────────────┐
         │   REMOTE (GitHub)        │  ← histórico partilhado
         └──────────────────────────┘
```

**Implicações:**
- Podes fazer N commits locais sem ninguém ver.
- Não precisas de internet para versionar.
- `git reset` apaga commits locais, sem afetar o GitHub.
- A regra de proteção só atua **na hora do push** para `main`.

---

## 5. Múltiplas branches em paralelo

Cenário simples — duas features independentes:

```
                                  ●  feature/healthchecks-v2
                                 /
main  ●─────●─────●─────●────●──╯
                              \
                               ●  feature/upload-validation
```

Cada branch tem o seu próprio histórico. `git switch` muda os ficheiros no disco para refletir a branch ativa. Cada uma vai dar origem a **um PR independente**.

### Quando uma branch depende de outra (Stacked PRs)

Cenário: feature B precisa de algo que ainda está na feature A em review.

```
git switch main
git switch -c feature/A          ← branch A a partir de main
# commits da A
git switch -c feature/B          ← branch B a partir da A (não de main)
# commits da B
```

PR da A mergeia primeiro → PR da B faz rebase para apanhar o merge → mergeia.

> **Boa prática:** evitar Stacked PRs até ser mesmo necessário. Em equipa, são uma fonte clássica de conflitos.

---

## 6. Cheatsheet de comandos — Git no dia-a-dia

### Branches

| Comando | Para quê |
|---|---|
| `git switch main` | Mudar para `main` (preferido em vez de `git checkout`). |
| `git switch -c feature/X` | Criar e mudar para nova branch. |
| `git branch` | Listar branches locais. |
| `git branch -a` | Listar branches locais **e** remotas. |
| `git branch -d feature/X` | Apagar branch local (depois do merge). |
| `git push origin --delete feature/X` | Apagar branch remota. |

### Trabalhar e sincronizar

| Comando | Para quê |
|---|---|
| `git status` | Ver ficheiros modificados, staged, branch atual. |
| `git add <ficheiro>` | Pôr ficheiro na staging area. |
| `git add .` | Pôr tudo (cuidado com sensitivos — confiar no `.gitignore`). |
| `git commit -m "msg"` | Criar commit com mensagem inline. |
| `git push` | Enviar commits da branch atual para o remoto. |
| `git push -u origin feature/X` | Push + linkar branch local à remota (primeira vez). |
| `git pull` | Trazer commits do remoto para o local. |
| `git fetch` | Apenas atualizar refs (sem mexer nos teus ficheiros). |

### Inspecionar histórico

| Comando | Para quê |
|---|---|
| `git log --oneline -10` | Últimos 10 commits, uma linha cada. |
| `git log --graph --oneline --all` | Grafo das branches. |
| `git diff` | Mudanças não-staged. |
| `git diff --staged` | Mudanças staged (prontas para commit). |
| `git show <sha>` | Conteúdo de um commit específico. |

### Desfazer

| Comando | Para quê | Cuidado |
|---|---|---|
| `git restore <ficheiro>` | Descartar alterações não-staged. | Perde alterações. |
| `git restore --staged <ficheiro>` | Tirar de staging (mantém alteração). | Seguro. |
| `git reset HEAD~1` | Apagar último commit, mantém alterações. | Local apenas. |
| `git reset --hard HEAD~1` | Apagar último commit e alterações. | **Perde tudo**. |
| `git revert <sha>` | Cria commit que desfaz outro. | **Sempre seguro**, recomendado em produção. |

### GitHub CLI

| Comando | Para quê |
|---|---|
| `gh repo view --web` | Abrir o repo no browser. |
| `gh pr create --fill` | Criar PR com title/body do último commit. |
| `gh pr checks` | Ver estado dos checks do PR atual. |
| `gh pr merge` | Mergeia o PR (squash/merge/rebase, conforme repo). |

---

## 7. Recap conceptual rápido

- **Branch** é um *ponteiro para um commit*. Nada mais. Por isso criar branches é instantâneo.
- **HEAD** é onde tu estás. Move quando fazes `commit`, `switch`, `reset`.
- **`origin`** é o nome convencional do remoto principal (ex.: GitHub).
- **`origin/main`** é a *última versão da `main` que vimos no remoto*. Pode estar atrás do `main` real do GitHub se outra pessoa fez push.
- **Pipeline as Code** é a peça que muda CI/CD de "ferramenta de admin" para "código revisado como qualquer outro".
- **Branch protection** transforma o CI de decorativo em **gate**: sem CI verde, não há merge.

---

## 8. O que vem a seguir — Mês 3

| # | Tarefa | Estado |
|---|---|---|
| 16 | Criar repo GitHub | ✅ |
| 17 | CI básico — `mvn test` no pipeline | ✅ |
| 18 | Build da imagem Docker no pipeline | ⏳ |
| 19 | Push da imagem para GHCR (registry) | ⏳ |
| 20 | Deploy automático para o cluster `kind` | ⏳ |
| 21 | GitOps com ArgoCD | ⏳ |
| 22 | README do portfolio | ⏳ |

**Próxima sessão:** Tarefa #18 — estender o `ci.yml` para fazer `docker build` da imagem `backup-api` em cada push, com tag pelo SHA do commit (princípio da imutabilidade).
