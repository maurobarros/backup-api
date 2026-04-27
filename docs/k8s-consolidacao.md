# Backup API on Kubernetes — Stack & Reference

Documento de consolidação do projeto backup-api a correr em Kubernetes (OrbStack local). Reúne a arquitetura, objetos, fluxos de comunicação e referência rápida de comandos `kubectl`.

---

## Visão geral

Aplicação Spring Boot REST que faz upload/download de ficheiros para Google Cloud Storage e mantém histórico em Postgres. Em desenvolvimento local, o GCS é substituído por um emulador (`fake-gcs-server`).

**Componentes:**

- **backup-api** — API REST stateless, 2 réplicas, escrita em Spring Boot.
- **postgres** — base de dados (stateful, com PVC).
- **fake-gcs** — emulador local de Google Cloud Storage (substituível por GCS real em produção).

---

## Arquitetura em três camadas

A distinção entre **ficheiros declarativos**, **objetos no cluster** e **execução** é fundamental para perceber Kubernetes.

```
┌─────────────────────────────────────────────────────────────────┐
│ CAMADA 1 — Ficheiros no Mac (~/backup-api/)                     │
├─────────────────────────────────────────────────────────────────┤
│  Código:    src/, pom.xml, application.properties               │
│  Imagem:    Dockerfile, .dockerignore                           │
│  K8s:       k8s/configmap-backup-api.yaml                       │
│             k8s/deployment-backup-api.yaml                      │
│             k8s/deployment-postgres.yaml                        │
│             k8s/deployment-fake-gcs.yaml                        │
│             k8s/service-backup-api.yaml      (NodePort)         │
│             k8s/service-postgres.yaml        (ClusterIP)        │
│             k8s/service-fake-gcs.yaml        (ClusterIP)        │
│             k8s/pvc-postgres.yaml                               │
│  (Secret backup-api-secrets criado via CLI, sem ficheiro)       │
└────────────────────────────┬────────────────────────────────────┘
                             │ kubectl apply / docker build
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ CAMADA 2 — Objetos no cluster (etcd)                            │
├─────────────────────────────────────────────────────────────────┤
│  ConfigMap: backup-api-config                                   │
│  Secret:    backup-api-secrets                                  │
│  PVC:       postgres-pvc ──► PV: pvc-xxxxxxxx (1Gi, local-path) │
│  Deployments:                                                   │
│    - postgres-deployment       (replicas: 1)                    │
│    - fake-gcs-deployment       (replicas: 1)                    │
│    - backup-api-deployment     (replicas: 2)                    │
│  ReplicaSets: gerados por cada Deployment                       │
│  Services:                                                      │
│    - postgres-service          (ClusterIP, 5432)                │
│    - fake-gcs-service          (ClusterIP, 4443)                │
│    - backup-api-service        (NodePort,  8080:30080)          │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ CAMADA 3 — Pods em execução (1 nó: orbstack)                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────┐   ┌──────────────────────┐             │
│  │ Pod: postgres       │   │ Pod: fake-gcs        │             │
│  │  Container postgres │   │  Container fake-gcs  │             │
│  │  /var/lib/.../data ◄┼─┐ │  (storage efémero)   │             │
│  └─────────────────────┘ │ └──────────────────────┘             │
│                          │                                      │
│  ┌─────────────────────┐ │ ┌──────────────────────┐             │
│  │ Pod: backup-api #1  │ │ │ Pod: backup-api #2   │             │
│  │  Container java     │ │ │  Container java      │             │
│  │  envFrom: CM+Secret │ │ │  envFrom: CM+Secret  │             │
│  └─────────────────────┘ │ └──────────────────────┘             │
└──────────────────────────┼──────────────────────────────────────┘
                           │
                           ▼
                  PV no disco da VM OrbStack
                  /var/lib/rancher/k3s/storage/...
```

---

## Comunicação

### Tráfego externo (Mac → cluster)

```
localhost:30080 ──► NodePort backup-api-service ──► Pod backup-api #1 ou #2
                    (load balancing entre as 2 réplicas)
```

### Tráfego interno (pod → pod)

```
Pod backup-api ──► postgres-service:5432 ──► Pod postgres
               ──► fake-gcs-service:4443 ──► Pod fake-gcs
```

A resolução `nome-do-service → IP` acontece via CoreDNS interno do cluster.

### Acesso de debug

```
Mac ──► kubectl port-forward ──► (túnel direto a pod ou service)
```

Usado para ligar DBeaver ao Postgres sem expor o Service externamente.

---

## Injeção de configuração

O Deployment é a "ponte" entre o ConfigMap/Secret e as env vars dentro do container.

```
ConfigMap "backup-api-config"   ──envFrom──► env vars no container backup-api:
  • SPRING_DATASOURCE_URL                    SPRING_DATASOURCE_URL
  • SPRING_DATASOURCE_USERNAME               SPRING_DATASOURCE_USERNAME
  • STORAGE_EMULATOR_HOST                    STORAGE_EMULATOR_HOST

Secret "backup-api-secrets"     ──secretKeyRef──► env var no container backup-api:
  • SPRING_DATASOURCE_PASSWORD               SPRING_DATASOURCE_PASSWORD

Secret "backup-api-secrets"     ──secretKeyRef──► env var no container postgres:
  • POSTGRES_PASSWORD                        POSTGRES_PASSWORD
```

A app Spring Boot lê as env vars no `application.properties` via `${SPRING_DATASOURCE_URL:fallback}`.

---

## Persistência (PVC)

O Postgres é o único componente stateful. Tem PVC para que os dados sobrevivam a reinícios do pod.

```
Pod postgres
  └── volumeMount: /var/lib/postgresql/data (subPath: postgres)
                    │
                    ▼
                  PVC postgres-pvc (1Gi, ReadWriteOnce)
                    │
                    ▼
                  PV pvc-xxxxxxxx
                    │
                    ▼
                  Pasta no nó: /var/lib/rancher/k3s/storage/...
```

`subPath: postgres` isola o Postgres numa subpasta do volume, evitando conflito com `lost+found/` na raiz.

---

## Liveness e Readiness probes

Ambos os endpoints expostos via Spring Boot Actuator.

| Probe | Endpoint | Delay | Período | Falhas | Ação |
|---|---|---|---|---|---|
| **Liveness** | `/actuator/health/liveness` | 30s | 10s | 3 | mata e recria container |
| **Readiness** | `/actuator/health/readiness` | 10s | 5s | 3 | tira pod do Service |

**Distinção crítica:**

- **Liveness fail** → reinício do container (deadlocks, processos travados).
- **Readiness fail** → pod removido do Service mas mantido vivo (BD em baixo, arranque lento, sobrecarga).

A readiness probe é o que protege os utilizadores durante rolling updates: pods novos só entram em rotação quando responderem 200.

---

## Mapa de classificação stateless vs stateful

| Componente | Tipo | PVC | Réplicas | Razão |
|---|---|---|---|---|
| backup-api | stateless | ❌ | 2 | só processa, sem dados locais |
| postgres | stateful | ✅ | 1 | dados são valor do negócio |
| fake-gcs (dev) | stateful | ❌ (dev only) | 1 | substituído por GCS real em prod |

Em produção, fake-gcs desaparece — a app fala diretamente com Google Cloud Storage. O Postgres fica como está, com PVC apontando a um disco gerido pelo cloud provider em vez de `local-path`.

---

## Cheatsheet `kubectl`

### Inspeção

```bash
kubectl get pods                            # lista pods
kubectl get pods -A                         # todos os namespaces
kubectl get pods -o wide                    # com IP, nó
kubectl get pods -l app=backup-api          # filtra por label
kubectl get all                             # tudo (pods + svc + deploy + rs)
kubectl get nodes
kubectl get configmaps                      # ou: cm
kubectl get secrets
kubectl get pvc
kubectl get pv
kubectl get endpoints <service>             # IPs dos pods do service
```

Atalhos: `po`, `svc`, `deploy`, `cm`, `rs`, `ns`.

### Detalhes

```bash
kubectl describe pod <nome>                 # detalhes + Events (debug)
kubectl describe deployment <nome>
kubectl describe svc <nome>
kubectl get pod <nome> -o yaml              # YAML completo
kubectl get pod <nome> -o json | jq         # filtrar com jq
```

A secção **Events** do `describe` mostra falhas de probes, imagem não encontrada, OOMKills, etc.

### Logs

```bash
kubectl logs <pod>
kubectl logs <pod> -f                       # follow live
kubectl logs <pod> --tail=50
kubectl logs <pod> --since=10m
kubectl logs <pod> -c <container>           # se houver vários containers
kubectl logs deploy/backup-api-deployment   # de qualquer pod do deployment
kubectl logs <pod> --previous               # logs do container anterior (após restart)
```

### Exec — entrar dentro de um pod

```bash
kubectl exec -it <pod> -- bash              # shell interativo
kubectl exec -it <pod> -- sh                # se não houver bash
kubectl exec <pod> -- env                   # listar env vars
kubectl exec <pod> -- ls /app               # comando único
kubectl exec deploy/backup-api -- env       # contra deployment
```

### Port-forward — túnel local

```bash
kubectl port-forward <pod> 5432:5432
kubectl port-forward svc/postgres-service 5432:5432
kubectl port-forward deploy/postgres 5432:5432
```

Ctrl+C fecha o túnel.

### Rollouts

```bash
kubectl rollout status deployment/<nome>
kubectl rollout history deployment/<nome>
kubectl rollout undo deployment/<nome>                    # rollback
kubectl rollout undo deployment/<nome> --to-revision=3
kubectl rollout restart deployment/<nome>                 # recria pods
kubectl rollout pause deployment/<nome>
kubectl rollout resume deployment/<nome>
```

### Apply / delete / edit

```bash
kubectl apply -f file.yaml
kubectl apply -f k8s/                       # toda a pasta
kubectl delete -f file.yaml
kubectl delete pod <nome>                   # Deployment recria
kubectl delete deployment <nome>
kubectl edit deployment <nome>              # editor ao vivo
```

**Atenção:** `delete` é destrutivo, sem confirmação. Verificar context antes.

### Scaling

```bash
kubectl scale deployment <nome> --replicas=5
kubectl scale deployment <nome> --replicas=0       # mata tudo, sem apagar deployment
```

### Contexto e namespaces

```bash
kubectl config get-contexts
kubectl config current-context
kubectl config use-context <nome>
kubectl get pods -n kube-system
kubectl config set-context --current --namespace=<ns>
```

### Métricas

```bash
kubectl top nodes              # CPU/RAM por nó
kubectl top pods               # CPU/RAM por pod
```

### Expor rapidamente

```bash
kubectl expose deployment <nome> --port=8080 --type=NodePort
```

### Criar Secrets / ConfigMaps de forma imperativa

```bash
kubectl create secret generic <nome> \
  --from-literal=KEY1=val1 --from-literal=KEY2=val2

kubectl create configmap <nome> \
  --from-literal=KEY=val \
  --from-file=config.json
```

### Dry-run + YAML

```bash
kubectl create deployment myapp --image=nginx --dry-run=client -o yaml
```

Útil para gerar templates rapidamente.

### Events

```bash
kubectl get events --sort-by=.metadata.creationTimestamp
kubectl get events -w                   # follow live
```

### API discovery

```bash
kubectl api-resources                   # todos os tipos
kubectl explain pod.spec                # documentação inline
kubectl explain deployment.spec.template.spec.containers
```

`explain` é o melhor amigo quando se escreve YAMLs.

---

## Top 10 do dia-a-dia

```bash
kubectl get pods                            # 1. ver pods
kubectl describe pod <pod>                  # 2. inspecionar
kubectl logs <pod> -f                       # 3. logs ao vivo
kubectl exec -it <pod> -- bash              # 4. entrar
kubectl apply -f file.yaml                  # 5. aplicar
kubectl rollout restart deployment/<nome>   # 6. recriar pods
kubectl rollout undo deployment/<nome>      # 7. rollback
kubectl port-forward svc/<svc> <p>:<p>     # 8. túnel
kubectl get events --sort-by=...            # 9. eventos
kubectl describe deploy <nome>              # 10. detalhes deployment
```

---

## Conceitos a fixar

### Ficheiro vs cluster

O ficheiro YAML é apenas **a intenção registada**. O cluster (etcd) é a **fonte de verdade do que está a correr**. Apagar o ficheiro não apaga o objeto. Fazer `kubectl edit` no cluster sem atualizar o ficheiro cria divergência silenciosa — risco real em equipas. Workflows GitOps (Flux, ArgoCD) eliminam esta divergência forçando o cluster a refletir o Git continuamente.

### Pod e container

O **container** está dentro do **pod**, não o contrário. Um pod pode ter vários containers que partilham rede e volumes (ex.: app + sidecar de logging).

### Hierarquia de objetos

```
Deployment ──cria──► ReplicaSet ──cria──► Pods
```

Deployment trata de versões e rollouts; ReplicaSet garante o número de réplicas; Pods são a execução real.

### ConfigMap/Secret e mudanças em runtime

Mudar um ConfigMap ou Secret **não atualiza pods já em execução**. Env vars são lidas no arranque e ficam em memória. Para apanhar o novo valor: `kubectl rollout restart deployment/<nome>`. Em produção, ferramentas como Reloader fazem isto automaticamente.

### Stateless vs stateful (cattle vs pets)

- **Stateless** ("gado") — descartável, intercambiável, sem PVC, qualquer réplica serve. Ex.: API, worker, frontend.
- **Stateful** ("estimação") — único, com identidade, com PVC, idealmente em StatefulSet. Ex.: BD, message broker.

### Tipos de Service

- **ClusterIP**: só dentro do cluster (default). De fora exige `port-forward`.
- **NodePort**: abre porta no nó (30000-32767), acessível de fora.
- **LoadBalancer**: cria load balancer no cloud provider com IP público.

### Secrets — base64 ≠ segurança

Secrets do K8s codificam valores em base64 (não encriptam). A segurança real vem de RBAC + encryption at rest do etcd + ferramentas externas (Vault, External Secrets Operator).

### Probes — o filtro de qualidade

Sem probes, o K8s assume "Running = saudável" e envia tráfego para pods com bug. Readiness probe protege os utilizadores durante rolling updates: o K8s só substitui pods velhos quando os novos estão genuinamente prontos.
