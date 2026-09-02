# 🧠 MindMax V4 — Multi-agent Android

MindMax V4 é um aplicativo Android nativo (Kotlin + Jetpack Compose) que roda
**múltiplos agentes de IA em conjunto** para responder, planejar, pesquisar
e auditar pedidos do usuário. Foi reescrito do zero a partir do
`AgenteAutonomo` (V1/V2), com:

- **Jetpack Compose** (Material 3, dark-only).
- **Room 2.8.4** para persistência (mensagens, agentes, auditoria, memória, tarefas).
- **Criptografia** da chave de API via `EncryptedSharedPreferences` + Android Keystore.
- **Streaming de tokens** do LLM via OkHttp SSE.
- **Múltiplos provedores** gratuitos (Groq, OpenRouter, Cloudflare Workers
  AI, GitHub Models, OpenAI) + endpoint customizado.
- **Foreground service opcional** que pode reconectar em background se você
  habilitar — *off por padrão* por compatibilidade com Android 15.

> **Uso pessoal.** O APK é `release-debug-signed` para uso pessoal — não
> há restrições do Google Play porque o app não é distribuído pela loja.

---

## ✨ Funcionalidades

### 🤖 Sistema multi-agente
7 agentes default semeados na primeira execução:

| id             | papel                | responsabilidade                          |
|----------------|----------------------|-------------------------------------------|
| `coordinator`  | Coordenador          | decide quais agentes usar                 |
| `planner`      | Planejador           | decompõe pedidos em passos                |
| `researcher`   | Pesquisador          | reúne contexto                             |
| `executor`     | Executor             | produz a resposta                          |
| `auditor`      | Auditor              | revisa consistência e segurança           |
| `memory`       | Memória              | persiste longo prazo                       |
| `communication`| Comunicação          | formata a saída                            |

Pipeline: `programmer (slash commands) → persistência → coordinator JSON →
waves paralelas via async{}.awaitAll() → agregação → cross-audit opcional`.

### 🔗 Provedores LLM suportados
- **Groq** (free tier generoso; `llama-3.3-70b-versatile` default)
- **OpenRouter** (free tier com `HTTP-Referer`/`X-Title`)
- **Cloudflare Workers AI** (precisa de Account ID)
- **GitHub Models**
- **OpenAI** (caso você queira usar)
- **Custom endpoint** (qualquer compatível com OpenAI)

### 🗄️ Banco de dados local (Room)
- `messages` — chat por `conversationId`
- `agents` — 7 default + custom; chave é string id
- `audit_logs` — correlação REQUEST / RESPONSE / AGENT_DECISION / SECURITY / ERROR
- `settings` — row singleton; `apiKey` é sempre um *sentinel* (`__ENC__:...`)
- `tasks` — fila de tarefas com status / priority / retries
- `memories` — long-term context, com TTL opcional

### 🔒 Segurança
A chave de API NUNCA toca SQLite em texto puro. O `SettingsEntity.apiKey`
guarda apenas um sentinel; a chave real fica em
`EncryptedSharedPreferences` (`mindmax_secure`), com `MasterKey` AES-256-GCM
gerado pelo **Android Keystore**. Migração automática copia qualquer chave
legada em texto puro para o store seguro no primeiro launch e grava uma
entrada `AuditType.SECURITY`.

### 📱 Interface Compose
Single-Activity com `NavigationBar` inferior:
- **Chat** — `LazyColumn` com bubbles USER/AGENT/SYSTEM + entrada com
  Send. Auto-scroll, `imePadding`.
- **Agentes** — lista dos 7 agentes com badge de cor, tipo, prioridade,
  switch de ativo/inativo.
- **Auditoria** �� log das últimas 500 entradas com filtro de cor por status.
- **Configurações** — provider picker, base URL custom, modelo, chave
  mascarada, sliders temperature/top-p/max-tokens, toggles de
  multi-agente/cross-audit/serviço/auto-start, danger zone.

### 🛰️ Background (opt-in)
`MindMaxForegroundService` (type=`dataSync`, channel
`mindmax_v4_channel`, IMPORTANCE_LOW) é **off por padrão** no primeiro
launch. Para ativar: Configurações → Serviço em background = ON.
`BootReceiver` religa se você marcou também Iniciar automaticamente.

---

## 🚀 Como instalar

### 1. Compilar via GitHub Actions (recomendado)
O workflow em `.github/workflows/build-apk.yml` produz dois APKs:

- `mindmax-v4-debug-apk` — instalável, debug-signed.
- `mindmax-v4-release-apk` — release-debug-signed (instalável, sem minify).

Após cada push, abra a aba **Actions** no GitHub → clique na run mais
recente → faça download do artefato.

> O repositório é público → builds ilimitados no GitHub Actions.

### 2. Instalar no celular
Transfira o APK para o celular e abra-o. O Android vai pedir permissão
pra instalar de fonte desconhecida (basta permitir uma vez para o
navegador/gerenciador de arquivos usado).

### 3. Configurar a chave
1. Abra o app.
2. Vá em **Config** (o último item da barra inferior).
3. Escolha o provedor (ex.: **Groq**).
4. Cole a chave em "Chave de API" (o campo é mascarado). Toque em **Salvar**.
5. Volte para **Chat** e envie uma mensagem.

### Comandos rápidos
- `/help` — lista comandos.
- `/agents` — descreve os 7 agentes default.
- `/audit` — aponta para a aba Auditoria.
- `/reset` — instrução para limpar dados.

Tudo fora desses prefixos vai para o Coordenador.

---

## 🛠️ Versões da stack

| Componente          | Versão       | Por quê                                       |
|---------------------|--------------|------------------------------------------------|
| Kotlin              | 2.3.10       | 2.4.x ainda não tem KSP estável.               |
| KSP                 | 2.3.10       | mesmo bloco.                                  |
| AGP                 | 9.3.0        | required for compileSdk 36/37.                |
| Gradle              | 9.5.0        | AGP 9.3 mínimo.                                |
| Room                | 2.8.4        | Room 3.0 dropa suporte a Kotlin 2.3.x.         |
| Compose BOM         | 2026.08.00   | estável para Compose 1.12.x.                  |
| JDK                 | 17 (Temurin) | requerido pelo AGP 9.x.                        |

---

## 🐛 Solução de problemas

### "Provider returned no message choices."
- Chave de API ausente ou incorreta. Salve de novo em Configurações.
- Modelo inválido para o provedor. Troque o campo **Modelo**.

### App não responde após minimizar
- Foreground service está **off por padrão** (intencional).
- Ative em Configurações → Serviço em background.
- Se o OEM (Xiaomi/Oppo/Honor) mata o app: desative otimização
  de bateria para o MindMax.

### Resposta do Coordenador vira `["executor"]`
- Esperado quando o LLM retorna JSON inválido (a pipeline cai no
  fallback). Olhe Auditoria → AGENT_DECISION para ver a saída crua.

### CI falha com `KSP for X is missing`
Bumpe `ksp = "X"` em `gradle/libs.versions.toml` para a versão exata
indicada no log do Actions.

---

## 📁 Estrutura

```
app/src/main/java/dev/mindmax/v4
├── MindMaxApp.kt / MainActivity.kt
├── core/
│   ├── di/ServiceLocator.kt
│   └── prefs/  (SecureKeyStore + AppPrefs)
├── data/
│   ├── db/  (MindMaxDatabase, Converters, DefaultAgents)
│   ├── entity/, dao/, repo/
├── llm/  (Provider, LlmClient, AuthInterceptor, SseParser…)
├── agent/ (AgentRuntime, ConversationProgrammer, AgentEvent)
├── audit/ (AuditLogger)
├── service/ (MindMaxForegroundService, BootReceiver, NetworkObserver)
└── ui/  (nav, chat, settings, agents, audit, theme)
```

---

## 📜 Licença

Uso pessoal. Sem garantia. Sem reivindicação.
